pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "grom"

include(":sdk")
include(":engine")
include(":app")
include(":testapps:samecert")
include(":testapps:foreigncert")
include(":examples:connect")
include(":examples:embedded")
