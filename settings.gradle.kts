import java.time.Duration
import java.time.temporal.ChronoUnit

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
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
