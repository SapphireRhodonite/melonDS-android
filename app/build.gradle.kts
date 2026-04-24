import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    signingConfigs {
        create("release") {
            val props = gradleLocalProperties(rootDir, providers)
            (props["MELONDS_KEYSTORE"] as String?)?.let { storeFile = file(it) }
            storePassword = props["MELONDS_KEYSTORE_PASSWORD"] as String? ?: ""
            keyAlias = props["MELONDS_KEY_ALIAS"] as String? ?: ""
            keyPassword = props["MELONDS_KEY_PASSWORD"] as String? ?: ""
        }
    }

    namespace = "me.magnum.melonds"
    compileSdk = AppConfig.compileSdkVersion
    ndkVersion = AppConfig.ndkVersion
    defaultConfig {
        applicationId = "me.magnum.melondualds"
        minSdk = AppConfig.minSdkVersion
        targetSdk = AppConfig.targetSdkVersion
        versionCode = AppConfig.versionCode
        versionName = AppConfig.versionName
        manifestPlaceholders["appName"] = "@string/app_name"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -Wno-write-strings")
            }
        }
        vectorDrawables.useSupportLibrary = true
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".dev"
        }
    }

    flavorDimensions.add("version")
    flavorDimensions.add("build")
    productFlavors {
        create("playStore") {
            dimension = "version"
        }
        create("gitHub") {
            dimension = "version"
            isDefault = true
        }

        create("prod") {
            dimension = "build"
            isDefault = true
        }
        create("nightly") {
            dimension = "build"
            applicationIdSuffix = ".nightly"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        // Adds exported schema location as test app assets.
        getByName("androidTest").assets.directories += "$projectDir/schemas"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

androidComponents {
    onVariants(selector().withName("gitHubProdDebug")) { variant ->
        // Keep prod-debug distinct from other debug/release variants.
        variant.manifestPlaceholders.put("appName", "debug melonDualDS")
        variant.sources.res?.addStaticSourceDirectory("src/nightly/res")
    }
}

val vulkanShaderSources = listOf(
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_InterpSpansShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_BinCombinedShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_CalculateWorkOffsetsShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_SortWorkShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_TriRasterShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_TriRasterBaseShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_TriRasterCompatShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_DepthBlendShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_FinalPassShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_CaptureLineExportShader.comp"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsRasterShader.vert"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsRasterShader.frag"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsNoColorShader.frag"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsClearShader.frag"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsFinalShader.vert"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsEdgeShader.frag"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsFogShader.frag"),
    rootProject.file("melonDS-android-lib/src/android/renderer/VulkanCompositorShader.comp"),
    rootProject.file("melonDS-android-lib/src/android/renderer/VulkanSurfacePresenter.vert"),
    rootProject.file("melonDS-android-lib/src/android/renderer/VulkanSurfacePresenter.frag"),
)

val vulkanShaderHeaders = listOf(
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_InterpSpansShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_BinCombinedShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_CalculateWorkOffsetsShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_SortWorkShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_TriRasterShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_TriRasterBaseShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_TriRasterCompatShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_DepthBlendShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_FinalPassShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_CaptureLineExportShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsRasterShaderVertexData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsRasterShaderFragmentData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsNoColorShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsClearShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsFinalShaderVertexData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsEdgeShaderData.h"),
    rootProject.file("melonDS-android-lib/src/GPU3D_Vulkan_GraphicsFogShaderData.h"),
    rootProject.file("melonDS-android-lib/src/android/renderer/VulkanCompositorShaderData.h"),
    rootProject.file("melonDS-android-lib/src/android/renderer/VulkanSurfacePresenterVertexShaderData.h"),
    rootProject.file("melonDS-android-lib/src/android/renderer/VulkanSurfacePresenterFragmentShaderData.h"),
)

val regenerateVulkanSpirv by tasks.registering(Exec::class) {
    group = "build"
    description = "Regenerates embedded Vulkan SPIR-V headers from Vulkan GLSL sources."
    executable = "bash"
    args(rootProject.file("scripts/regenerate_vulkan_spirv.sh").absolutePath)
    workingDir = rootProject.projectDir

    inputs.file(rootProject.file("scripts/regenerate_vulkan_spirv.sh"))
    inputs.files(vulkanShaderSources)
    outputs.files(vulkanShaderHeaders)
}

val checkVulkanSpirv by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks whether embedded Vulkan SPIR-V headers are synchronized with GLSL sources."
    executable = "bash"
    args(rootProject.file("scripts/regenerate_vulkan_spirv.sh").absolutePath, "--check")
    workingDir = rootProject.projectDir

    inputs.file(rootProject.file("scripts/regenerate_vulkan_spirv.sh"))
    inputs.files(vulkanShaderSources)
    outputs.upToDateWhen { false }
}

tasks.named("preBuild").configure {
    dependsOn(regenerateVulkanSpirv)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    val gitHubImplementation by configurations

    implementation(projects.masterswitch)
    implementation(projects.rcheevosApi)
    implementation(projects.common)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.rxjava)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work)
    implementation(libs.android.material)

    implementation(platform(libs.compose.bom))
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil)
    implementation(libs.gson)
    implementation(libs.hilt)
    implementation(libs.kotlin.serialization)
    implementation(libs.kotlin.serialization.protobuf)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.rx)
    implementation(libs.picasso)
    implementation(libs.markwon)
    implementation(libs.markwon.imagepicasso)
    implementation(libs.markwon.linkify)
    implementation(libs.rxjava)
    implementation(libs.rxjava.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    gitHubImplementation(libs.retrofit)
    gitHubImplementation(libs.retrofit.converter.kotlinx)

    ksp(libs.hilt.compiler)
    ksp(libs.hilt.compiler.android)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
