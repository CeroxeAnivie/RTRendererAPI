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
    }
}

rootProject.name = "RTRendererAPI"

nmcpSettings {
    centralPortal {
        username = providers.gradleProperty("centralUsername")
            .orElse(providers.environmentVariable("CENTRAL_USERNAME"))
            .orElse(providers.systemProperty("centralUsername"))
            .getOrElse("")
        password = providers.gradleProperty("centralPassword")
            .orElse(providers.environmentVariable("CENTRAL_PASSWORD"))
            .orElse(providers.systemProperty("centralPassword"))
            .getOrElse("")
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
