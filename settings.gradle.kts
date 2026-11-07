pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("stagingRepository").orNull?.let { repositoryUri ->
            maven {
                name = "localStaging"
                url = uri(repositoryUri)
            }
        }
        mavenCentral()
        // Central is authoritative for released coordinates; an unreleased checkout may
        // resolve the freshly staged coordinates from the repository produced by this build.
        maven {
            name = "localBuildFallback"
            url = uri(settingsDir.resolve("build/repository"))
            content { includeGroup("top.ceroxe.rt") }
        }
    }
}

rootProject.name = "RTRendererAPI"

// The public contracts and the external upstream bridge are the complete runtime.
include("renderer-api")
include("renderer-rtxpt")
