rootProject.name = "Dialer"
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
//        maven { setUrl("https://developer.huawei.com/repo/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
//        maven { setUrl("https://artifactory-external.vkpartner.ru/artifactory/maven") }
        maven { setUrl("https://www.jitpack.io") }
//        maven { setUrl("https://developer.huawei.com/repo/") }
        mavenLocal()
    }
}
include(":app")
