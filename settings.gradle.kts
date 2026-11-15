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
        // chesslib is published through JitPack, not Maven Central
        maven("https://jitpack.io") {
            content { includeGroup("com.github.bhlangonijr") }
        }
    }
}
rootProject.name = "ChessAssist"
include(":app")
