pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "RTRendererAPI"

/*
 * Keep the public host contract isolated from the Vulkan implementation. This
 * makes accidental implementation leakage a compile-time/build-time failure.
 */
include("renderer-api")
include("renderer-core")
