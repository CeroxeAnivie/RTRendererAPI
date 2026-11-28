#pragma once

#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl/client.h>
#include <bcrypt.h>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>
#include <windows.h>
#include "rtxpt_startup_progress.h"

namespace rtr::upstream {

class D3D12PipelineCache final {
public:
    static constexpr uint64_t MaximumFileBytes = 64ull * 1024 * 1024;
    static constexpr uint64_t MaximumBlobBytes = MaximumFileBytes - 256;

    bool Open(ID3D12Device* device, const std::filesystem::path& cacheRoot) noexcept {
        try {
            if (cacheRoot.empty()) return false;
            D3D12_FEATURE_DATA_SHADER_CACHE support{};
            if (FAILED(device->CheckFeatureSupport(D3D12_FEATURE_SHADER_CACHE, &support, sizeof(support)))
                || !(support.SupportFlags & D3D12_SHADER_CACHE_SUPPORT_LIBRARY)) return false;

            Microsoft::WRL::ComPtr<IDXGIFactory4> factory;
            Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
            DXGI_ADAPTER_DESC1 desc{};
            if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))
                || FAILED(factory->EnumAdapterByLuid(device->GetAdapterLuid(), IID_PPV_ARGS(&adapter)))
                || FAILED(adapter->GetDesc1(&desc))) return false;

            m_identity = std::to_string(desc.VendorId) + " " + std::to_string(desc.DeviceId) + " "
                + std::to_string(desc.SubSysId) + " " + std::to_string(desc.Revision);
            if (IsReparsePoint(cacheRoot)) return false;
            m_directory = cacheRoot / "D3D12PipelineCache";
            m_file = m_directory / "pipeline-library.bin";
            std::filesystem::create_directories(m_directory);
            if (IsReparsePoint(m_directory)) return false;
            RemoveAbandonedTemporaryFiles();

            Microsoft::WRL::ComPtr<ID3D12Device1> device1;
            if (FAILED(device->QueryInterface(IID_PPV_ARGS(&device1)))) {
                return false;
            }
            if (ReadBlob()) {
                const HRESULT loaded = device1->CreatePipelineLibrary(m_blob.data(), m_blob.size(), IID_PPV_ARGS(&m_library));
                if (FAILED(loaded)) {
                    m_blob.clear();
                    std::error_code ignored;
                    if (!IsReparsePoint(m_file)) std::filesystem::remove(m_file, ignored);
                    m_invalidated = true;
                } else {
                    m_cacheLoaded = true;
                }
            }
            if (!m_library && FAILED(device1->CreatePipelineLibrary(nullptr, 0, IID_PPV_ARGS(&m_library)))) {
                return false;
            }
            return true;
        } catch (...) {
            m_library.Reset();
            m_blob.clear();
            return false;
        }
    }

    HRESULT CreateComputePipeline(ID3D12Device* device, const std::wstring& name,
        const D3D12_COMPUTE_PIPELINE_STATE_DESC& desc, ID3D12PipelineState** pipeline) noexcept {
        std::lock_guard lock(m_mutex);
        m_activeName = Narrow(name);
        startup::beginPso(m_activeName);
        const bool cacheHit = m_library
            && SUCCEEDED(m_library->LoadComputePipeline(name.c_str(), &desc, IID_PPV_ARGS(pipeline)));
        startup::recordPsoCacheLookup(cacheHit);
        if (cacheHit) {
            ++m_hits;
            startup::finishPso();
            return S_OK;
        }
        ++m_misses;
        const HRESULT created = device->CreateComputePipelineState(&desc, IID_PPV_ARGS(pipeline));
        if (SUCCEEDED(created) && m_library && SUCCEEDED(m_library->StorePipeline(name.c_str(), *pipeline))) {
            m_dirty = true;
            ++m_stores;
        }
        startup::finishPso();
        return created;
    }

    void Save() noexcept {
        if (!m_library || !m_dirty || m_file.empty()) return;
        try {
            const SIZE_T size = m_library->GetSerializedSize();
            if (!size || size > MaximumBlobBytes) return;
            std::vector<uint8_t> bytes(size);
            if (FAILED(m_library->Serialize(bytes.data(), bytes.size()))) return;

            const auto digest = Sha256(bytes.data(), bytes.size());
            const auto temporary = m_file.parent_path()
                / ("pipeline-library-" + std::to_string(GetCurrentProcessId()) + ".tmp");
            TemporaryFileCleanup cleanup(temporary);
            std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
            if (!output) return;
            output << "RTR-D3D12-PIPELINE-LIBRARY-1\n" << m_identity << '\n' << bytes.size() << '\n' << digest << '\n';
            output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
            output.flush();
            if (!output) return;
            output.close();
            if (!output || !MoveFileExW(temporary.c_str(), m_file.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
                return;
        } catch (...) { }
    }

    bool CacheLoaded() const noexcept { return m_cacheLoaded; }
    bool CacheInvalidated() const noexcept { return m_invalidated; }
    static std::string Digest(const uint8_t* bytes, size_t size) { return Sha256(bytes, size); }
    std::string Status() const {
        std::lock_guard lock(m_mutex);
        return "D3D12 compute pipeline cache: loaded=" + std::to_string(m_cacheLoaded)
            + ", invalidated=" + std::to_string(m_invalidated) + ", hits=" + std::to_string(m_hits)
            + ", driverCreates=" + std::to_string(m_misses) + ", stored=" + std::to_string(m_stores);
    }
private:
    class TemporaryFileCleanup final {
    public:
        explicit TemporaryFileCleanup(std::filesystem::path path) : m_path(std::move(path)) {}
        ~TemporaryFileCleanup() {
            std::error_code ignored;
            std::filesystem::remove(m_path, ignored);
        }
        TemporaryFileCleanup(const TemporaryFileCleanup&) = delete;
        TemporaryFileCleanup& operator=(const TemporaryFileCleanup&) = delete;
    private:
        std::filesystem::path m_path;
    };

    void RemoveAbandonedTemporaryFiles() noexcept {
        try {
            constexpr std::wstring_view prefix = L"pipeline-library-";
            constexpr std::wstring_view suffix = L".tmp";
            std::error_code iterationError;
            for (std::filesystem::directory_iterator entry(m_directory, iterationError), end;
                !iterationError && entry != end; entry.increment(iterationError)) {
                const auto& path = entry->path();
                const auto name = path.filename().native();
                if (!entry->is_regular_file(iterationError) || iterationError
                    || name.size() <= prefix.size() + suffix.size()
                    || name.compare(0, prefix.size(), prefix) != 0
                    || name.compare(name.size() - suffix.size(), suffix.size(), suffix) != 0) continue;

                const auto processText = name.substr(prefix.size(), name.size() - prefix.size() - suffix.size());
                if (processText.find_first_not_of(L"0123456789") != std::wstring::npos) continue;
                DWORD processId = 0;
                bool validProcessId = true;
                for (wchar_t digit : processText) {
                    const DWORD value = static_cast<DWORD>(digit - L'0');
                    if (processId > (MAXDWORD - value) / 10) {
                        validProcessId = false;
                        break;
                    }
                    processId = processId * 10 + value;
                }
                if (!validProcessId || !processId) continue;

                HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, processId);
                if (!process) {
                    if (GetLastError() != ERROR_INVALID_PARAMETER) continue;
                } else {
                    const DWORD state = WaitForSingleObject(process, 0);
                    CloseHandle(process);
                    if (state != WAIT_OBJECT_0) continue;
                }

                std::error_code removeError;
                std::filesystem::remove(path, removeError);
            }
        } catch (...) { }
    }

    static bool IsReparsePoint(const std::filesystem::path& path) noexcept {
        const DWORD attributes = GetFileAttributesW(path.c_str());
        return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    }

    static std::string Narrow(const std::wstring& value) {
        if (value.empty()) return {};
        const int length = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
            nullptr, 0, nullptr, nullptr);
        std::string result(static_cast<size_t>(length), '\0');
        WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), length, nullptr, nullptr);
        return result;
    }

    static std::string Sha256(const uint8_t* bytes, size_t size) {
        struct Handles {
            BCRYPT_ALG_HANDLE algorithm = nullptr;
            BCRYPT_HASH_HANDLE hash = nullptr;
            ~Handles() { if (hash) BCryptDestroyHash(hash); if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0); }
        } handles;
        if (BCryptOpenAlgorithmProvider(&handles.algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0)
            throw std::runtime_error("Cannot open SHA-256 provider for the D3D12 pipeline cache");
        DWORD objectSize = 0;
        DWORD resultSize = 0;
        DWORD hashSize = 0;
        if (BCryptGetProperty(handles.algorithm, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objectSize),
                sizeof(objectSize), &resultSize, 0) < 0
            || BCryptGetProperty(handles.algorithm, BCRYPT_HASH_LENGTH, reinterpret_cast<PUCHAR>(&hashSize),
                sizeof(hashSize), &resultSize, 0) < 0 || hashSize != 32)
            throw std::runtime_error("Cannot query SHA-256 provider for the D3D12 pipeline cache");
        std::vector<uint8_t> object(objectSize);
        if (BCryptCreateHash(handles.algorithm, &handles.hash, object.data(), objectSize, nullptr, 0, 0) < 0
            || BCryptHashData(handles.hash, const_cast<PUCHAR>(bytes), static_cast<ULONG>(size), 0) < 0)
            throw std::runtime_error("Cannot hash the D3D12 pipeline cache");
        uint8_t digest[32]{};
        if (BCryptFinishHash(handles.hash, digest, sizeof(digest), 0) < 0)
            throw std::runtime_error("Cannot finish hashing the D3D12 pipeline cache");
        static constexpr char hex[] = "0123456789abcdef";
        std::string result;
        result.reserve(64);
        for (uint8_t value : digest) { result.push_back(hex[value >> 4]); result.push_back(hex[value & 0x0f]); }
        return result;
    }

    bool ReadBlob() {
        std::error_code error;
        if (IsReparsePoint(m_file)) { m_invalidated = true; return false; }
        if (!std::filesystem::is_regular_file(m_file, error) || error) return false;
        std::ifstream input(m_file, std::ios::binary);
        std::string format, identity, sizeText, digest;
        if (!std::getline(input, format) || !std::getline(input, identity)
            || !std::getline(input, sizeText) || !std::getline(input, digest)
            || format != "RTR-D3D12-PIPELINE-LIBRARY-1" || identity != m_identity
            || digest.size() != 64 || digest.find_first_not_of("0123456789abcdef") != std::string::npos) {
            std::filesystem::remove(m_file, error);
            m_invalidated = true;
            return false;
        }
        uint64_t size = 0;
        try {
            size = std::stoull(sizeText);
        } catch (...) {
            std::filesystem::remove(m_file, error);
            m_invalidated = true;
            return false;
        }
        if (!size || size > MaximumBlobBytes) {
            std::filesystem::remove(m_file, error);
            m_invalidated = true;
            return false;
        }
        m_blob.resize(static_cast<size_t>(size));
        input.read(reinterpret_cast<char*>(m_blob.data()), static_cast<std::streamsize>(m_blob.size()));
        if (input.gcount() != static_cast<std::streamsize>(m_blob.size()) || input.peek() != std::char_traits<char>::eof()
            || Sha256(m_blob.data(), m_blob.size()) != digest) {
            m_blob.clear();
            std::filesystem::remove(m_file, error);
            m_invalidated = true;
            return false;
        }
        const auto minimumFileSize = static_cast<uint64_t>(format.size() + identity.size() + sizeText.size() + digest.size() + 4) + size;
        if (std::filesystem::file_size(m_file, error) != minimumFileSize || error) {
            m_blob.clear();
            std::filesystem::remove(m_file, error);
            m_invalidated = true;
            return false;
        }
        return true;
    }

    std::filesystem::path m_file;
    std::filesystem::path m_directory;
    std::string m_identity;
    std::string m_activeName;
    std::vector<uint8_t> m_blob;
    Microsoft::WRL::ComPtr<ID3D12PipelineLibrary> m_library;
    mutable std::mutex m_mutex;
    bool m_dirty = false;
    bool m_cacheLoaded = false;
    bool m_invalidated = false;
    uint64_t m_hits = 0;
    uint64_t m_misses = 0;
    uint64_t m_stores = 0;
};

inline std::wstring ComputePipelineCacheName(const void* rootSignature, size_t rootSignatureSize,
    const void* shader, size_t shaderSize, UINT nodeMask, D3D12_PIPELINE_STATE_FLAGS flags) {
    std::vector<uint8_t> identity;
    auto append = [&identity](const void* data, size_t size) {
        const auto* bytes = static_cast<const uint8_t*>(data);
        identity.insert(identity.end(), bytes, bytes + size);
    };
    const uint64_t rootLength = rootSignatureSize;
    const uint64_t shaderLength = shaderSize;
    append(&rootLength, sizeof(rootLength));
    append(rootSignature, rootSignatureSize);
    append(&shaderLength, sizeof(shaderLength));
    append(shader, shaderSize);
    append(&nodeMask, sizeof(nodeMask));
    append(&flags, sizeof(flags));
    const std::string digest = D3D12PipelineCache::Digest(identity.data(), identity.size());
    std::wstring name = L"compute-";
    name.reserve(name.size() + digest.size());
    for (char character : digest) name.push_back(static_cast<wchar_t>(character));
    return name;
}

} // namespace rtr::upstream
