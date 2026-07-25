pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "rtrendererStaging"
                    url = uri(providers.gradleProperty("stagingRepository").get())
                    metadataSources {
                        // Forcing POM metadata prevents Gradle module metadata from hiding a broken
                        // Maven publication from downstream Maven-compatible consumers.
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("top.ceroxe.rt")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "rtrenderer-published-consumer-smoke"
