#include "streamline_sdk.hpp"

#include <Windows.h>

#include <sstream>
#include <stdexcept>
#include <utility>
#include <vector>

#include <sl_helpers_vk.h>

#include "streamline_diagnostics.hpp"
#include "streamline_feature_selection.hpp"
#include "streamline_types.hpp"

namespace rtrenderer::nvidia {
namespace {

constexpr const char* kProjectId = "3e5c3742-74c1-46fa-81c9-64a4419959ea";

std::wstring environmentValue(const wchar_t* name) {
    const DWORD required = GetEnvironmentVariableW(name, nullptr, 0);
    if (required == 0) return {};
    std::wstring value(required, L'\0');
    const DWORD written = GetEnvironmentVariableW(name, value.data(), required);
    if (written == 0 || written >= required) return {};
    value.resize(written);
    return value;
}

std::wstring parentDirectory(HMODULE module) {
    std::wstring path(32768, L'\0');
    const DWORD length = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
    if (length == 0 || length >= path.size()) return {};
    path.resize(length);
    const std::size_t separator = path.find_last_of(L"\\/");
    return separator == std::wstring::npos ? std::wstring{} : path.substr(0, separator);
}

HMODULE currentModule() {
    HMODULE module = nullptr;
    const BOOL resolved = GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&currentModule),
            &module
    );
    return resolved == FALSE ? nullptr : module;
}

void appendNames(std::ostringstream& output, uint32_t count, const char* const* names) {
    for (uint32_t index = 0; index < count; ++index) {
        if (index != 0) output << ',';
        if (names == nullptr || names[index] == nullptr) {
            throw std::runtime_error("Streamline returned a null requirement name");
        }
        output << names[index];
    }
}

void appendFeature(
        std::vector<sl::Feature>& features,
        std::int32_t mask,
        std::int32_t bit,
        sl::Feature feature
) {
    if ((mask & bit) != 0) features.push_back(feature);
}

const char* wireName(sl::Feature feature) {
    if (feature == sl::kFeatureDLSS) return "DLSS";
    if (feature == sl::kFeatureNIS) return "NIS";
    if (feature == sl::kFeatureDLSS_G) return "DLSS_FRAME_GENERATION";
    if (feature == sl::kFeatureDLSS_RR) return "DLSS_RAY_RECONSTRUCTION";
    if (feature == sl::kFeatureReflex) return "REFLEX";
    if (feature == sl::kFeaturePCL) return "PCL";
    throw std::runtime_error("unsupported Streamline feature wire name");
}

}

class StreamlineSdkSession::Impl final {
public:
    ~Impl() {
        close();
    }

    std::string preflight(std::int32_t requestedFeatures) {
        close();
        clearStreamlineDiagnostic();
        if (requestedFeatures == 0) return "failed\nno Streamline feature was requested";
        if ((requestedFeatures & kStreamlineFrameGeneration) != 0) {
            // Normalize the native boundary too: callers cannot request DLSS-G without loading
            // the Reflex pacing and PCL marker plugins that make its cadence contract valid.
            requestedFeatures |= kStreamlineReflex | kStreamlinePcl;
        }
        if (!load()) return "failed\nfailed to load sl.interposer.dll";

        std::vector<sl::Feature> features;
        appendFeature(features, requestedFeatures, kStreamlineDlss, sl::kFeatureDLSS);
        appendFeature(features, requestedFeatures, kStreamlineNis, sl::kFeatureNIS);
        appendFeature(features, requestedFeatures, kStreamlineFrameGeneration, sl::kFeatureDLSS_G);
        appendFeature(features, requestedFeatures, kStreamlineRayReconstruction, sl::kFeatureDLSS_RR);
        appendFeature(features, requestedFeatures, kStreamlineReflex, sl::kFeatureReflex);
        appendFeature(features, requestedFeatures, kStreamlinePcl, sl::kFeaturePCL);
        if (features.empty()) return fail("unknown Streamline feature request");

        const wchar_t* pluginPath = pluginDirectory_.empty() ? nullptr : pluginDirectory_.c_str();
        sl::Preferences preferences = {};
        preferences.pathsToPlugins = pluginPath == nullptr ? nullptr : &pluginPath;
        preferences.numPathsToPlugins = pluginPath == nullptr ? 0 : 1;
        preferences.featuresToLoad = features.data();
        preferences.numFeaturesToLoad = static_cast<uint32_t>(features.size());
        preferences.engine = sl::EngineType::eCustom;
        preferences.engineVersion = RTRENDERER_VERSION;
        preferences.projectId = kProjectId;
        preferences.renderAPI = sl::RenderAPI::eVulkan;
        preferences.logMessageCallback = captureStreamlineDiagnostic;
        // OTA plugins would make a signed deployment nondeterministically bind a cached version.
        preferences.flags = sl::PreferenceFlags::eDisableCLStateTracking
                | sl::PreferenceFlags::eDisableDebugText
                | sl::PreferenceFlags::eUseManualHooking
                | sl::PreferenceFlags::eUseFrameBasedResourceTagging;
        const sl::Result initialized = init_(preferences, sl::kSDKVersion);
        if (initialized != sl::Result::eOk) return fail("slInit=" + streamlineResultName(initialized));
        initialized_ = true;
        requestedFeatures_ = requestedFeatures;
        try {
            std::ostringstream output;
            output << "ready\nslInit=0\n";
            for (sl::Feature feature : features) {
                sl::FeatureRequirements requirements = {};
                const sl::Result result = requirements_(feature, requirements);
                if (result != sl::Result::eOk) {
                    return fail("slGetFeatureRequirements=" + streamlineResultName(result));
                }
                sl::FeatureVersion version = {};
                const sl::Result versionResult = featureVersion_(feature, version);
                if (versionResult != sl::Result::eOk) {
                    return fail("slGetFeatureVersion=" + streamlineResultName(versionResult));
                }
                output << wireName(feature) << '\t' << 0 << '\t'
                        << requirements.vkNumGraphicsQueuesRequired << '\t'
                        << requirements.vkNumComputeQueuesRequired << '\t'
                        << requirements.vkNumOpticalFlowQueuesRequired << '\t';
                appendNames(output, requirements.vkNumInstanceExtensions, requirements.vkInstanceExtensions);
                output << '\t';
                appendNames(output, requirements.vkNumDeviceExtensions, requirements.vkDeviceExtensions);
                output << '\t';
                appendNames(output, requirements.vkNumFeatures12, requirements.vkFeatures12);
                output << '\t';
                appendNames(output, requirements.vkNumFeatures13, requirements.vkFeatures13);
                output << '\t' << version.versionSL.major
                       << '\t' << version.versionSL.minor
                       << '\t' << version.versionSL.build << '\n';
            }
            return output.str();
        } catch (...) {
            // A malformed requirement report is a failed preflight, not a partially live SDK.
            close();
            throw;
        }
    }

    std::int32_t bindVulkan(
            const StreamlineVulkanBinding& binding,
            StreamlineApiBindings& output
    ) {
        output = {};
        if (!initialized_ || setVulkanInfo_ == nullptr || binding.instance == 0
                || binding.physicalDevice == 0 || binding.device == 0
                || binding.computeQueueIndex < 0 || binding.computeQueueFamily < 0
                || binding.graphicsQueueIndex < 0 || binding.graphicsQueueFamily < 0
                || binding.opticalFlowQueueIndex < 0 || binding.opticalFlowQueueFamily < 0) {
            return static_cast<std::int32_t>(sl::Result::eErrorInvalidParameter);
        }
        if ((binding.requiredFeatures & ~requestedFeatures_) != 0) {
            return static_cast<std::int32_t>(sl::Result::eErrorInvalidParameter);
        }

        sl::VulkanInfo info = {};
        info.instance = reinterpret_cast<VkInstance>(binding.instance);
        info.physicalDevice = reinterpret_cast<VkPhysicalDevice>(binding.physicalDevice);
        info.device = reinterpret_cast<VkDevice>(binding.device);
        info.computeQueueIndex = static_cast<uint32_t>(binding.computeQueueIndex);
        info.computeQueueFamily = static_cast<uint32_t>(binding.computeQueueFamily);
        info.graphicsQueueIndex = static_cast<uint32_t>(binding.graphicsQueueIndex);
        info.graphicsQueueFamily = static_cast<uint32_t>(binding.graphicsQueueFamily);
        info.opticalFlowQueueIndex = static_cast<uint32_t>(binding.opticalFlowQueueIndex);
        info.opticalFlowQueueFamily = static_cast<uint32_t>(binding.opticalFlowQueueFamily);
        info.useNativeOpticalFlowMode = binding.useNativeOpticalFlowMode;
        // A loaded plugin and resolved entry points are not hardware capability evidence. Query
        // Streamline against the actual Vulkan physical device before publishing any execution
        // bindings, so unsupported generations fail at handoff instead of being advertised ACTIVE.
        if (isFeatureSupported_ == nullptr) {
            close();
            return static_cast<std::int32_t>(sl::Result::eErrorInvalidState);
        }
        sl::AdapterInfo adapterInfo = {};
        adapterInfo.vkPhysicalDevice = info.physicalDevice;
        std::int32_t supportedFeatures = 0;
        sl::Result dlssSupport = sl::Result::eOk;
        sl::Result nisSupport = sl::Result::eOk;
        sl::Result rayReconstructionSupport = sl::Result::eOk;
        for (const auto& candidate : {
                std::pair{kStreamlineDlss, sl::kFeatureDLSS},
                std::pair{kStreamlineNis, sl::kFeatureNIS},
                std::pair{kStreamlineRayReconstruction, sl::kFeatureDLSS_RR}
        }) {
            if ((requestedFeatures_ & candidate.first) == 0) continue;
            const sl::Result support = isFeatureSupported_(candidate.second, adapterInfo);
            if (candidate.first == kStreamlineDlss) dlssSupport = support;
            if (candidate.first == kStreamlineNis) nisSupport = support;
            if (candidate.first == kStreamlineRayReconstruction) rayReconstructionSupport = support;
            if (support == sl::Result::eOk) supportedFeatures |= candidate.first;
        }
        sl::Result lowLatencySupport = sl::Result::eOk;
        if ((requestedFeatures_ & (kStreamlineReflex | kStreamlinePcl)) != 0) {
            for (sl::Feature dependency : {sl::kFeatureReflex, sl::kFeaturePCL}) {
                const sl::Result support = isFeatureSupported_(dependency, adapterInfo);
                if (support != sl::Result::eOk) {
                    lowLatencySupport = support;
                    break;
                }
            }
            if (lowLatencySupport == sl::Result::eOk) {
                supportedFeatures |= kStreamlineReflex | kStreamlinePcl;
            }
        }
        sl::Result generationSupport = sl::Result::eOk;
        if ((requestedFeatures_ & kStreamlineFrameGeneration) != 0) {
            generationSupport = isFeatureSupported_(sl::kFeatureDLSS_G, adapterInfo);
            if (generationSupport == sl::Result::eOk && lowLatencySupport != sl::Result::eOk) {
                generationSupport = lowLatencySupport;
            }
            if (generationSupport == sl::Result::eOk) {
                supportedFeatures |= kStreamlineFrameGeneration;
            }
        }
        const StreamlineFeatureSelection selection = selectStreamlineFeatures(
                requestedFeatures_, binding.requiredFeatures, supportedFeatures
        );
        if (selection.missingRequired != 0) {
            sl::Result failure = generationSupport;
            if ((selection.missingRequired & kStreamlineDlss) != 0) failure = dlssSupport;
            else if ((selection.missingRequired & kStreamlineNis) != 0) failure = nisSupport;
            else if ((selection.missingRequired & (kStreamlineReflex | kStreamlinePcl)) != 0) {
                failure = lowLatencySupport;
            }
            else if ((selection.missingRequired & kStreamlineRayReconstruction) != 0) {
                failure = rayReconstructionSupport;
            }
            close();
            return static_cast<std::int32_t>(failure);
        }
        boundFeatures_ = selection.executable;
        if (boundFeatures_ == 0) return static_cast<std::int32_t>(sl::Result::eOk);
        const sl::Result deviceResult = setVulkanInfo_(info);
        if (deviceResult != sl::Result::eOk) {
            close();
            return static_cast<std::int32_t>(deviceResult);
        }

        StreamlineApiBindings candidate{};
        if (!resolveBindings(reinterpret_cast<VkDevice>(binding.device), candidate)) {
            close();
            return static_cast<std::int32_t>(sl::Result::eErrorInvalidState);
        }
        if ((boundFeatures_ & (kStreamlineReflex | kStreamlinePcl))
                == (kStreamlineReflex | kStreamlinePcl)) {
            sl::ReflexOptions options = {};
            options.mode = sl::ReflexMode::eLowLatency;
            const sl::Result reflexResult = candidate.reflexSetOptions(options);
            if (reflexResult != sl::Result::eOk) {
                close();
                return static_cast<std::int32_t>(reflexResult);
            }
        }
        output = candidate;
        return static_cast<std::int32_t>(sl::Result::eOk);
    }

    void close() noexcept {
        closeImpl();
    }

    void releaseResources(const sl::ViewportHandle& viewport) noexcept {
        if (!initialized_ || freeResources_ == nullptr) return;
        // Reflex and PCL are frame-generation dependencies, but they do not own the
        // viewport resources created by evaluateFeature. Releasing them here asks the SDK
        // to tear down state it never allocated and is particularly fragile when NRD owns
        // the same Vulkan device. Keep this list limited to resource-producing features.
        for (sl::Feature feature : allocatedResourceFeaturesList()) {
            // slFreeResources is the required fence between evaluateFeature and slShutdown.
            // A failed individual release must not prevent the remaining feature owners from
            // receiving their cleanup opportunity; shutdown remains the final fallback.
            freeResources_(feature, viewport);
        }
    }

    void unloadFrameGeneration() {
        if (!initialized_ || setFeatureLoaded_ == nullptr) {
            throw std::runtime_error("Streamline cannot unload DLSS-G before initialization");
        }
        if ((boundFeatures_ & kStreamlineFrameGeneration) == 0) return;
        const sl::Result result = setFeatureLoaded_(sl::kFeatureDLSS_G, false);
        if (result != sl::Result::eOk) {
            throw std::runtime_error(
                    "slSetFeatureLoaded(DLSS_G,false)=" + streamlineResultName(result)
            );
        }
        boundFeatures_ &= ~kStreamlineFrameGeneration;
    }

private:
    void closeImpl() noexcept {
        if (initialized_ && shutdown_ != nullptr) {
            shutdown_();
        }
        initialized_ = false;
        requestedFeatures_ = 0;
        boundFeatures_ = 0;
        init_ = nullptr;
        shutdown_ = nullptr;
        requirements_ = nullptr;
        featureVersion_ = nullptr;
        freeResources_ = nullptr;
        isFeatureSupported_ = nullptr;
        setVulkanInfo_ = nullptr;
        setFeatureLoaded_ = nullptr;
        getFeatureFunction_ = nullptr;
        pluginDirectory_.clear();
        // The module remains loaded for the process lifetime; OS process teardown owns the vendor
        // worker threads. This is deliberate because the SDK does not provide a safe shutdown
        // barrier for a borrowed Vulkan device.
    }

public:
    bool initialized() const noexcept {
        return initialized_;
    }

    std::int32_t requestedFeatures() const noexcept {
        return requestedFeatures_;
    }

    std::int32_t boundFeatures() const noexcept {
        return boundFeatures_;
    }

private:
    bool load() {
        if (module_ == nullptr) {
            const std::wstring configuredDirectory = environmentValue(L"RTRENDERER_STREAMLINE_PLUGIN_DIR");
            if (!configuredDirectory.empty()) {
                const std::wstring explicitPath = configuredDirectory + L"\\sl.interposer.dll";
                module_ = LoadLibraryW(explicitPath.c_str());
            } else {
                const std::wstring sidecarDirectory = parentDirectory(currentModule());
                if (!sidecarDirectory.empty()) {
                    const std::wstring sidecarPath = sidecarDirectory + L"\\sl.interposer.dll";
                    module_ = LoadLibraryW(sidecarPath.c_str());
                }
                if (module_ == nullptr) module_ = LoadLibraryW(L"sl.interposer.dll");
            }
            if (module_ == nullptr) return false;
            pluginDirectory_ = configuredDirectory.empty() ? parentDirectory(module_) : configuredDirectory;
        }
        // closeImpl deliberately keeps the interposer module loaded for process safety while it
        // clears every borrowed function pointer. A later preflight must therefore rebuild the
        // complete core table even when no LoadLibrary call is needed. Treating "module loaded"
        // as "bindings valid" turns the second slInit into a null indirect call.
        init_ = symbol<PFun_slInit>("slInit");
        shutdown_ = symbol<PFun_slShutdown>("slShutdown");
        requirements_ = symbol<PFun_slGetFeatureRequirements>("slGetFeatureRequirements");
        featureVersion_ = symbol<PFun_slGetFeatureVersion>("slGetFeatureVersion");
        freeResources_ = symbol<PFun_slFreeResources>("slFreeResources");
        isFeatureSupported_ = symbol<PFun_slIsFeatureSupported>("slIsFeatureSupported");
        setVulkanInfo_ = symbol<PFun_slSetVulkanInfo>("slSetVulkanInfo");
        setFeatureLoaded_ = symbol<PFun_slSetFeatureLoaded>("slSetFeatureLoaded");
        getFeatureFunction_ = symbol<PFun_slGetFeatureFunction>("slGetFeatureFunction");
        if (init_ == nullptr || shutdown_ == nullptr || requirements_ == nullptr
                || featureVersion_ == nullptr
                || freeResources_ == nullptr
                || isFeatureSupported_ == nullptr || setVulkanInfo_ == nullptr
                || setFeatureLoaded_ == nullptr
                || getFeatureFunction_ == nullptr) {
            close();
            return false;
        }
        return true;
    }

    bool resolveBindings(VkDevice device, StreamlineApiBindings& output) {
        output.device = device;
        output.getNewFrameToken = symbol<PFun_slGetNewFrameToken>("slGetNewFrameToken");
        output.setTagForFrame = symbol<PFun_slSetTagForFrame>("slSetTagForFrame");
        output.setConstants = symbol<PFun_slSetConstants>("slSetConstants");
        output.evaluateFeature = symbol<PFun_slEvaluateFeature>("slEvaluateFeature");
        output.freeResources = freeResources_;
        if (output.getNewFrameToken == nullptr || output.setTagForFrame == nullptr
                || output.setConstants == nullptr || output.evaluateFeature == nullptr
                || output.freeResources == nullptr) {
            return false;
        }

        if ((boundFeatures_ & kStreamlineDlss) != 0) {
            if (!resolveFeatureFunction(
                    sl::kFeatureDLSS, "slDLSSGetOptimalSettings", output.dlssOptimalSettings
            ) || !resolveFeatureFunction(sl::kFeatureDLSS, "slDLSSSetOptions", output.dlssSetOptions)) {
                return false;
            }
        }
        if ((boundFeatures_ & kStreamlineNis) != 0
                && !resolveFeatureFunction(sl::kFeatureNIS, "slNISSetOptions", output.nisSetOptions)) {
            return false;
        }
        if ((boundFeatures_ & kStreamlineFrameGeneration) != 0
                && (!resolveFeatureFunction(sl::kFeatureDLSS_G, "slDLSSGSetOptions", output.dlssGSetOptions)
                || !resolveFeatureFunction(sl::kFeatureDLSS_G, "slDLSSGGetState", output.dlssGGetState))) {
            return false;
        }
        if ((boundFeatures_ & (kStreamlineReflex | kStreamlinePcl))
                == (kStreamlineReflex | kStreamlinePcl)) {
            if (!resolveFeatureFunction(sl::kFeatureReflex, "slReflexSetOptions", output.reflexSetOptions)
                    || !resolveFeatureFunction(sl::kFeatureReflex, "slReflexSleep", output.reflexSleep)
                    || !resolveFeatureFunction(sl::kFeaturePCL, "slPCLSetMarker", output.pclSetMarker)) {
                return false;
            }
        }

        auto getDeviceProcAddress = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                GetProcAddress(module_, "vkGetDeviceProcAddr")
        );
        if (getDeviceProcAddress == nullptr) return false;
        output.createSwapchain = reinterpret_cast<PFN_vkCreateSwapchainKHR>(
                getDeviceProcAddress(device, "vkCreateSwapchainKHR")
        );
        output.destroySwapchain = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
                getDeviceProcAddress(device, "vkDestroySwapchainKHR")
        );
        output.getSwapchainImages = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
                getDeviceProcAddress(device, "vkGetSwapchainImagesKHR")
        );
        output.acquireNextImage = reinterpret_cast<PFN_vkAcquireNextImageKHR>(
                getDeviceProcAddress(device, "vkAcquireNextImageKHR")
        );
        output.queuePresent = reinterpret_cast<PFN_vkQueuePresentKHR>(
                getDeviceProcAddress(device, "vkQueuePresentKHR")
        );
        output.waitSemaphores = reinterpret_cast<PFN_vkWaitSemaphores>(
                getDeviceProcAddress(device, "vkWaitSemaphores")
        );
        output.deviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(
                getDeviceProcAddress(device, "vkDeviceWaitIdle")
        );
        return output.createSwapchain != nullptr && output.destroySwapchain != nullptr
                && output.getSwapchainImages != nullptr && output.acquireNextImage != nullptr
                && output.queuePresent != nullptr && output.waitSemaphores != nullptr
                && output.deviceWaitIdle != nullptr;
    }

    template <typename Function>
    Function* symbol(const char* name) const {
        return module_ == nullptr ? nullptr : reinterpret_cast<Function*>(GetProcAddress(module_, name));
    }

    template <typename Function>
    bool resolveFeatureFunction(sl::Feature feature, const char* name, Function*& target) {
        void* function = nullptr;
        if (getFeatureFunction_(feature, name, function) != sl::Result::eOk || function == nullptr) {
            return false;
        }
        target = reinterpret_cast<Function*>(function);
        return true;
    }

    std::string fail(const std::string& reason) {
        close();
        return "failed\n" + reason;
    }

    std::vector<sl::Feature> allocatedResourceFeaturesList() const {
        std::vector<sl::Feature> features;
        appendFeature(features, boundFeatures_, kStreamlineDlss, sl::kFeatureDLSS);
        appendFeature(features, boundFeatures_, kStreamlineNis, sl::kFeatureNIS);
        appendFeature(features, boundFeatures_, kStreamlineFrameGeneration, sl::kFeatureDLSS_G);
        appendFeature(features, boundFeatures_, kStreamlineRayReconstruction, sl::kFeatureDLSS_RR);
        return features;
    }

    HMODULE module_ = nullptr;
    PFun_slInit* init_ = nullptr;
    PFun_slShutdown* shutdown_ = nullptr;
    PFun_slGetFeatureRequirements* requirements_ = nullptr;
    PFun_slGetFeatureVersion* featureVersion_ = nullptr;
    PFun_slFreeResources* freeResources_ = nullptr;
    PFun_slIsFeatureSupported* isFeatureSupported_ = nullptr;
    PFun_slSetVulkanInfo* setVulkanInfo_ = nullptr;
    PFun_slSetFeatureLoaded* setFeatureLoaded_ = nullptr;
    PFun_slGetFeatureFunction* getFeatureFunction_ = nullptr;
    std::wstring pluginDirectory_;
    std::int32_t requestedFeatures_ = 0;
    std::int32_t boundFeatures_ = 0;
    bool initialized_ = false;
};

StreamlineSdkSession::StreamlineSdkSession() : impl_(std::make_unique<Impl>()) {
}

StreamlineSdkSession::~StreamlineSdkSession() = default;

std::string StreamlineSdkSession::preflight(std::int32_t requestedFeatures) {
    return impl_->preflight(requestedFeatures);
}

std::int32_t StreamlineSdkSession::bindVulkan(
        const StreamlineVulkanBinding& binding,
        StreamlineApiBindings& output
) {
    return impl_->bindVulkan(binding, output);
}

void StreamlineSdkSession::unloadFrameGeneration() {
    impl_->unloadFrameGeneration();
}

void StreamlineSdkSession::close() noexcept {
    impl_->close();
}

void StreamlineSdkSession::releaseResources(const sl::ViewportHandle& viewport) noexcept {
    impl_->releaseResources(viewport);
}


bool StreamlineSdkSession::initialized() const noexcept {
    return impl_->initialized();
}

std::int32_t StreamlineSdkSession::requestedFeatures() const noexcept {
    return impl_->requestedFeatures();
}

std::int32_t StreamlineSdkSession::boundFeatures() const noexcept {
    return impl_->boundFeatures();
}

}
