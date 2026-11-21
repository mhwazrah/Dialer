rootProject.name = "build-logic"
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // The app's version catalog, so the AGP version is declared once
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
