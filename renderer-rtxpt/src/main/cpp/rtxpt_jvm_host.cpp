// External process host for the unmodified upstream executable-relative runtime.
// This uses the public JNI Invocation API, not Java launcher internals.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <jni.h>
#include <algorithm>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#if defined(RTXPT_D3D_AGILITY_SDK_VERSION)
// D3D12 reads Agility selection from the process executable. Use the same SDK
// selection as upstream; the API DLL does not change the process-wide default.
extern "C" {
__declspec(dllexport) extern const UINT D3D12SDKVersion = RTXPT_D3D_AGILITY_SDK_VERSION;
__declspec(dllexport) extern const char* D3D12SDKPath = ".\\D3D12\\";
}
#endif

namespace {
struct Arguments {
    std::wstring jvm, classpath, main;
    std::vector<std::wstring> options, application;
};

std::string Utf8(std::wstring_view text) {
    if (text.empty()) return {};
    const int count = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(),
        static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (!count) throw std::runtime_error("Argument contains invalid UTF-16");
    std::string result(count, '\0');
    if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(),
        static_cast<int>(text.size()), result.data(), count, nullptr, nullptr))
        throw std::runtime_error("Cannot encode argument as UTF-8");
    return result;
}

// FindClass/GetStaticMethodID use JNI modified UTF-8 (UTF-16 code units,
// including paired surrogates), unlike VM option strings and console output.
std::string ModifiedUtf8(std::wstring_view text) {
    std::string result;
    for (const auto unit : text) {
        const auto value = static_cast<unsigned short>(unit);
        if (value && value < 0x80) result.push_back(static_cast<char>(value));
        else if (value < 0x800) {
            result.push_back(static_cast<char>(0xc0 | (value >> 6)));
            result.push_back(static_cast<char>(0x80 | (value & 0x3f)));
        } else {
            result.push_back(static_cast<char>(0xe0 | (value >> 12)));
            result.push_back(static_cast<char>(0x80 | ((value >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (value & 0x3f)));
        }
    }
    return result;
}

void Usage() {
    std::cout << "Set RTR_JVM_DLL (absolute jvm.dll), RTR_JVM_CLASSPATH and RTR_JVM_MAIN.\n"
        << "Optional RTR_JVM_OPTION_COUNT / RTR_JVM_OPTION_0..N-1 and "
        << "RTR_JVM_ARG_COUNT / RTR_JVM_ARG_0..N-1 contain exact argument values.\n"
        << "Process command-line arguments belong to the upstream RTXPT application.\n";
}

std::wstring Environment(const std::wstring& name, bool required = false) {
    SetLastError(ERROR_SUCCESS);
    const DWORD count = GetEnvironmentVariableW(name.c_str(), nullptr, 0);
    if (!count) {
        if (required) throw std::invalid_argument("Missing environment value: " + Utf8(name));
        return {};
    }
    std::wstring value(count, L'\0');
    SetLastError(ERROR_SUCCESS);
    const DWORD written = GetEnvironmentVariableW(name.c_str(), value.data(), count);
    if (!written && GetLastError() == ERROR_SUCCESS) {
        if (required) throw std::invalid_argument("Empty environment value: " + Utf8(name));
        return {};
    }
    if (!written || written >= count)
        throw std::runtime_error("Cannot read environment value: " + Utf8(name));
    value.resize(written);
    return value;
}

std::vector<std::wstring> EnvironmentArguments(const std::wstring& prefix, bool vmOptions) {
    const auto text = Environment(prefix + L"COUNT");
    size_t count = 0;
    for (const auto digit : text) {
        if (digit < L'0' || digit > L'9' || count > 4096)
            throw std::invalid_argument("Invalid argument count: " + Utf8(prefix + L"COUNT"));
        count = count * 10 + static_cast<size_t>(digit - L'0');
    }
    if (count > 4096) throw std::invalid_argument("Argument count exceeds 4096");
    std::vector<std::wstring> values;
    values.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        const auto name = prefix + std::to_wstring(i);
        auto value = Environment(name);
        // A counted main argument may be empty or absent. Both denote an
        // empty string, without shell parsing or delimiter restrictions.
        if (vmOptions && value.empty()) throw std::invalid_argument("Empty VM option: " + Utf8(name));
        if (vmOptions && value.rfind(L"-Djava.class.path=", 0) == 0)
            throw std::invalid_argument("Use RTR_JVM_CLASSPATH to set java.class.path");
        values.push_back(std::move(value));
    }
    return values;
}

Arguments ReadEnvironment() {
    Arguments result;
    result.jvm = Environment(L"RTR_JVM_DLL", true);
    result.classpath = Environment(L"RTR_JVM_CLASSPATH", true);
    result.main = Environment(L"RTR_JVM_MAIN", true);
    if (!std::filesystem::path(result.jvm).is_absolute())
        throw std::invalid_argument("RTR_JVM_DLL must be an absolute path");
    result.options = EnvironmentArguments(L"RTR_JVM_OPTION_", true);
    result.application = EnvironmentArguments(L"RTR_JVM_ARG_", false);
    return result;
}

int InvokeMain(JNIEnv* env, const Arguments& args) {
    auto className = args.main;
    std::replace(className.begin(), className.end(), L'.', L'/');
    const auto modifiedName = ModifiedUtf8(className);
    const jclass mainClass = env->FindClass(modifiedName.c_str());
    if (!mainClass) { env->ExceptionDescribe(); return 71; }
    const auto mainMethod = env->GetStaticMethodID(mainClass, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) { env->ExceptionDescribe(); env->DeleteLocalRef(mainClass); return 71; }
    const jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) { env->ExceptionDescribe(); env->DeleteLocalRef(mainClass); return 71; }
    const auto mainArgs = env->NewObjectArray(static_cast<jsize>(args.application.size()), stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (!mainArgs) { env->ExceptionDescribe(); env->DeleteLocalRef(mainClass); return 71; }
    for (jsize i = 0; i < static_cast<jsize>(args.application.size()); ++i) {
        const auto& value = args.application[i];
        static_assert(sizeof(wchar_t) == sizeof(jchar), "Windows UTF-16 is required");
        const auto string = env->NewString(reinterpret_cast<const jchar*>(value.data()),
            static_cast<jsize>(value.size()));
        if (!string) {
            env->ExceptionDescribe(); env->DeleteLocalRef(mainArgs);
            env->DeleteLocalRef(mainClass); return 71;
        }
        env->SetObjectArrayElement(mainArgs, i, string);
        env->DeleteLocalRef(string);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe(); env->DeleteLocalRef(mainArgs);
            env->DeleteLocalRef(mainClass); return 71;
        }
    }
    env->CallStaticVoidMethod(mainClass, mainMethod, mainArgs);
    const bool failed = env->ExceptionCheck();
    if (failed) env->ExceptionDescribe();
    env->DeleteLocalRef(mainArgs);
    env->DeleteLocalRef(mainClass);
    return failed ? 1 : 0;
}
}

int wmain() {
    try {
        const auto args = ReadEnvironment();
        const std::filesystem::path jvmPath(args.jvm);
        if (!std::filesystem::is_regular_file(jvmPath)) {
            std::cerr << "JVM library does not exist: " << Utf8(args.jvm) << '\n';
            return 66;
        }
        // Add only this selected runtime's bin directory for JVM dependencies.
        // Do not change PATH, the current directory, or any Java installation.
        const auto javaBin = jvmPath.parent_path().parent_path();
        const auto directory = AddDllDirectory(javaBin.c_str());
        if (!directory) {
            std::cerr << "Cannot register JVM dependency directory; Win32=" << GetLastError() << '\n';
            return 66;
        }
        const auto module = LoadLibraryExW(jvmPath.c_str(), nullptr,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
        if (!module) {
            const auto error = GetLastError();
            RemoveDllDirectory(directory);
            std::cerr << "Cannot load JVM library; Win32=" << error << '\n';
            return 66;
        }
        using CreateVm = jint(JNICALL*)(JavaVM**, void**, void*);
        const auto createVm = reinterpret_cast<CreateVm>(GetProcAddress(module, "JNI_CreateJavaVM"));
        if (!createVm) {
            std::cerr << "Selected DLL does not export JNI_CreateJavaVM\n";
            RemoveDllDirectory(directory); return 66;
        }
        std::vector<std::string> optionText;
        optionText.reserve(args.options.size() + 1);
        optionText.push_back("-Djava.class.path=" + Utf8(args.classpath));
        for (const auto& option : args.options) optionText.push_back(Utf8(option));
        std::vector<JavaVMOption> options;
        options.reserve(optionText.size());
        for (auto& text : optionText) options.push_back({text.data(), nullptr});
        JavaVMInitArgs init{};
        init.version = JNI_VERSION_1_8;
        init.nOptions = static_cast<jint>(options.size());
        init.options = options.data();
        init.ignoreUnrecognized = JNI_FALSE;
        JavaVM* vm = nullptr;
        JNIEnv* env = nullptr;
        const auto created = createVm(&vm, reinterpret_cast<void**>(&env), &init);
        if (created != JNI_OK) {
            std::cerr << "JNI_CreateJavaVM failed; JNI=" << created << '\n';
            RemoveDllDirectory(directory); return 70;
        }
        const auto status = InvokeMain(env, args);
        // Standard Invocation API waits for application non-daemon threads.
        const auto destroyed = vm->DestroyJavaVM();
        RemoveDllDirectory(directory);
        if (destroyed != JNI_OK) {
            std::cerr << "DestroyJavaVM failed; JNI=" << destroyed << '\n';
            return 72;
        }
        // Keep the JVM DLL loaded until process exit; its runtime owns native TLS.
        return status;
    } catch (const std::invalid_argument& error) {
        std::cerr << error.what() << '\n'; Usage(); return 64;
    } catch (const std::exception& error) {
        std::cerr << "JVM host failed: " << error.what() << '\n'; return 72;
    }
}
