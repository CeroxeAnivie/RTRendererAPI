import java.time.Duration
import java.time.temporal.ChronoUnit

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.gradleup.nmcp.settings") version "1.5.0"
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
        // Demo and published-consumer builds may need an unreleased checkout while retaining
        // the same coordinates as Central consumers. This repository is deliberately after
        // Central: a released artifact always wins, and the local publication is only a fallback.
        maven {
            name = "localBuildFallback"
            url = uri(settingsDir.resolve("build/repository"))
            content { includeGroup("top.ceroxe.rt") }
        }
    }
}

rootProject.name = "RTRendererAPI"

nmcpSettings {
    centralPortal {
        username = providers.gradleProperty("centralUsername").getOrElse("")
        password = providers.gradleProperty("centralPassword").getOrElse("")
        publishingType = "AUTOMATIC"
        publicationName = "RTRendererAPI-${providers.gradleProperty("project_version").get()}"
        validationTimeout = Duration.of(30, ChronoUnit.MINUTES)
    }
}

/*
 * Keep the public host contract isolated from the Vulkan implementation. This
 * makes accidental implementation leakage a compile-time/build-time failure.
 */
include("renderer-api")
include("renderer-core")
include("renderer-nvidia")
include("demos:hex-ball")
