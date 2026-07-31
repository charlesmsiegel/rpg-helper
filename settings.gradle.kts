pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "rpg-helper"

include(":pack")
include(":state")
include(":retrieval")
include(":model")
include(":builder")
include(":capabilities")
include(":routing")

include(":session")
include(":cli")

include(":app")
