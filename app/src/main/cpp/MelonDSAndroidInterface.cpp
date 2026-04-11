#include <vector>
#include <cstring>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include "JniEnvHandler.h"
#include "UriFileHandler.h"
#include "MelonDS.h"
#include "GPU3D_Vulkan.h"
#include "OpenGLContext.h"
#include "Platform.h"
#include "renderer/FrameQueue.h"
#include "renderer/VulkanOutput.h"
#include "retroachievements/RetroAchievementsManager.h"

JniEnvHandler* jniEnvHandler;

JavaVM* vm;
jobject androidUriFileHandler;
UriFileHandler* fileHandler;

namespace
{
constexpr jint kRendererCapOpenGl = 1 << 0;
constexpr jint kRendererCapVulkan = 1 << 1;

constexpr const char* kRequiredInstanceExtensions[] = {
    VK_KHR_SURFACE_EXTENSION_NAME,
    "VK_KHR_android_surface",
};

constexpr const char* kRequiredDeviceExtensions[] = {
    VK_KHR_SWAPCHAIN_EXTENSION_NAME,
    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
};

constexpr const char* kTimelineSemaphoreExtension = VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME;
constexpr const char* kDescriptorIndexingExtension = VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;

bool hasExtension(const char* extensionName, const std::vector<VkExtensionProperties>& extensions)
{
    for (const VkExtensionProperties& extension : extensions)
    {
        if (std::strcmp(extension.extensionName, extensionName) == 0)
            return true;
    }

    return false;
}

bool isApiAtLeast(uint32_t apiVersion, uint32_t major, uint32_t minor)
{
    const uint32_t versionMajor = VK_API_VERSION_MAJOR(apiVersion);
    const uint32_t versionMinor = VK_API_VERSION_MINOR(apiVersion);
    return (versionMajor > major) || (versionMajor == major && versionMinor >= minor);
}

bool hasRequiredInstanceExtensions()
{
    uint32_t extensionCount = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr) != VK_SUCCESS)
        return false;

    std::vector<VkExtensionProperties> extensions(extensionCount);
    if (vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount, extensions.data()) != VK_SUCCESS)
        return false;

    for (const char* requiredExtension : kRequiredInstanceExtensions)
    {
        if (!hasExtension(requiredExtension, extensions))
            return false;
    }

    return true;
}

bool hasRequiredDeviceExtensions(VkInstance instance, VkPhysicalDevice physicalDevice)
{
    uint32_t extensionCount = 0;
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extensionCount, nullptr) != VK_SUCCESS)
        return false;

    std::vector<VkExtensionProperties> extensions(extensionCount);
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extensionCount, extensions.data()) != VK_SUCCESS)
        return false;

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physicalDevice, &properties);
    const bool apiAtLeast12 = isApiAtLeast(properties.apiVersion, 1, 2);

    for (const char* requiredExtension : kRequiredDeviceExtensions)
    {
        if (!hasExtension(requiredExtension, extensions))
        {
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Warn,
                "MelonDSAndroidInterface: device '%s' missing extension %s",
                properties.deviceName,
                requiredExtension
            );
            return false;
        }
    }

    if (!apiAtLeast12)
    {
        if (!hasExtension(kTimelineSemaphoreExtension, extensions))
        {
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Warn,
                "MelonDSAndroidInterface: device '%s' missing extension %s",
                properties.deviceName,
                kTimelineSemaphoreExtension
            );
            return false;
        }
    }

    VkPhysicalDeviceTimelineSemaphoreFeatures timelineFeatures{};
    timelineFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;

    VkPhysicalDeviceDescriptorIndexingFeatures descriptorFeatures{};
    descriptorFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES;
    descriptorFeatures.pNext = &timelineFeatures;

    VkPhysicalDeviceFeatures2 features2{};
    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features2.pNext = &descriptorFeatures;
    auto getPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
    if (getPhysicalDeviceFeatures2 == nullptr)
    {
        getPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2KHR"));
    }

    if (getPhysicalDeviceFeatures2 != nullptr)
    {
        getPhysicalDeviceFeatures2(physicalDevice, &features2);
    }
    else
    {
        vkGetPhysicalDeviceFeatures(physicalDevice, &features2.features);
    }

    if (timelineFeatures.timelineSemaphore != VK_TRUE)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "MelonDSAndroidInterface: device '%s' missing feature timelineSemaphore",
            properties.deviceName
        );
        return false;
    }

    if (features2.features.shaderSampledImageArrayDynamicIndexing != VK_TRUE)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "MelonDSAndroidInterface: device '%s' missing feature shaderSampledImageArrayDynamicIndexing",
            properties.deviceName
        );
        return false;
    }

    if (descriptorFeatures.shaderSampledImageArrayNonUniformIndexing != VK_TRUE)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "MelonDSAndroidInterface: device '%s' missing feature shaderSampledImageArrayNonUniformIndexing; compatibility texture path will be used",
            properties.deviceName
        );
    }
    else if (!apiAtLeast12 && !hasExtension(kDescriptorIndexingExtension, extensions))
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "MelonDSAndroidInterface: device '%s' missing extension %s; compatibility texture path will be used",
            properties.deviceName,
            kDescriptorIndexingExtension
        );
    }

    return true;
}

bool createVulkanInstance(VkInstance* instance)
{
    if (!hasRequiredInstanceExtensions())
        return false;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "melonDS-android";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "melonDS";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = sizeof(kRequiredInstanceExtensions) / sizeof(kRequiredInstanceExtensions[0]);
    createInfo.ppEnabledExtensionNames = kRequiredInstanceExtensions;

    if (vkCreateInstance(&createInfo, nullptr, instance) != VK_SUCCESS)
        return false;

    return true;
}

bool pickGraphicsDevice(VkInstance instance, VkPhysicalDevice* physicalDevice, uint32_t* queueFamilyIndex)
{
    uint32_t physicalDeviceCount = 0;
    const VkResult enumerateResult = vkEnumeratePhysicalDevices(instance, &physicalDeviceCount, nullptr);
    if (enumerateResult != VK_SUCCESS || physicalDeviceCount == 0)
    {
        return false;
    }

    std::vector<VkPhysicalDevice> physicalDevices(physicalDeviceCount);
    vkEnumeratePhysicalDevices(instance, &physicalDeviceCount, physicalDevices.data());

    bool hasGraphicsQueue = false;
    for (const VkPhysicalDevice currentPhysicalDevice : physicalDevices)
    {
        if (!hasRequiredDeviceExtensions(instance, currentPhysicalDevice))
            continue;

        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(currentPhysicalDevice, &queueFamilyCount, nullptr);
        if (queueFamilyCount == 0)
            continue;

        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(currentPhysicalDevice, &queueFamilyCount, queueFamilies.data());
        for (uint32_t i = 0; i < queueFamilyCount; i++)
        {
            const VkQueueFamilyProperties& queueFamily = queueFamilies[i];
            if ((queueFamily.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0)
            {
                *physicalDevice = currentPhysicalDevice;
                *queueFamilyIndex = i;
                hasGraphicsQueue = true;
                break;
            }
        }

        if (hasGraphicsQueue)
            break;
    }

    return hasGraphicsQueue;
}

bool isVulkanRendererSupported()
{
    VkInstance instance = VK_NULL_HANDLE;
    if (!createVulkanInstance(&instance))
        return false;

    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    const bool result = pickGraphicsDevice(instance, &physicalDevice, &queueFamilyIndex);
    vkDestroyInstance(instance, nullptr);
    return result;
}

bool canInitializeVulkanRenderer()
{
    constexpr u64 kQuickValidationWaitTimeoutNs = 250'000'000ull;
    if (!isVulkanRendererSupported())
        return false;

    MelonDSAndroid::VulkanOutput vulkanOutput;
    if (!vulkanOutput.init())
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "canInitializeVulkanRenderer: VulkanOutput::init failed");
        return false;
    }

    Frame validationFrame{};
    constexpr uint32_t validationWidth = 64;
    constexpr uint32_t validationHeight = 64;

    if (!vulkanOutput.ensureFrameResources(&validationFrame, validationWidth, validationHeight))
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "canInitializeVulkanRenderer: ensureFrameResources failed");
        return false;
    }

    if (!vulkanOutput.validateFrameSubmission(&validationFrame, kQuickValidationWaitTimeoutNs))
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "canInitializeVulkanRenderer: validateFrameSubmission failed");
        return false;
    }

    return true;
}
}

extern "C"
{
JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_setup(JNIEnv* env, jobject thiz, jobject uriFileHandler)
{
    env->GetJavaVM(&vm);
    MelonDSAndroid::RetroAchievements::RetroAchievementsManager::SetJavaVm(vm);
    jniEnvHandler = new JniEnvHandler(vm);
    androidUriFileHandler = env->NewGlobalRef(uriFileHandler);
    fileHandler = new UriFileHandler(jniEnvHandler, androidUriFileHandler);

    MelonDSAndroid::fileHandler = fileHandler;
}

JNIEXPORT jlong JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_getEmulatorGlContext(JNIEnv* env, jobject thiz)
{
    if (!MelonDSAndroid::ensureOpenGlContext() || MelonDSAndroid::openGlContext == nullptr)
        return 0;

    return (jlong) MelonDSAndroid::openGlContext->GetContext();
}

JNIEXPORT jint JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_getRendererCapabilities(JNIEnv* env, jobject thiz)
{
    jint caps = kRendererCapOpenGl;
    if (isVulkanRendererSupported())
        caps |= kRendererCapVulkan;
    return caps;
}

JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_canInitializeVulkanRendererNative(JNIEnv* env, jobject thiz)
{
    return canInitializeVulkanRenderer() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_cleanup(JNIEnv* env, jobject thiz)
{
    MelonDSAndroid::RetroAchievements::RetroAchievementsManager::SetJavaVm(nullptr);
    env->DeleteGlobalRef(androidUriFileHandler);
    androidUriFileHandler = nullptr;
    vm = nullptr;

    if (MelonDSAndroid::openGlContext != nullptr)
    {
        MelonDSAndroid::openGlContext->DeInit();
        delete MelonDSAndroid::openGlContext;
    }
    delete fileHandler;
    delete jniEnvHandler;

    MelonDSAndroid::openGlContext = nullptr;
}
}
