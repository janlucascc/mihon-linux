pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "mihon-linux"

include(":domain")
include(":i18n")
include(":core:common")
include(":core:metro")
include(":core:archive")
include(":source-api")
include(":data")
include(":desktop")
