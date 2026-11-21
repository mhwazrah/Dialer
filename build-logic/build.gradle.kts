// Build-time bytecode patches for third-party libraries (see NoFakeVersionWarning.kt).
plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: at runtime the plugin sees the AGP (and its ASM) the build already applies
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.gradlePlugins.agp.get()}")
    compileOnly("org.ow2.asm:asm:9.9")
}

gradlePlugin {
    plugins {
        register("noFakeVersionWarning") {
            id = "mhw.no-fake-version-warning"
            implementationClass = "NoFakeVersionWarningPlugin"
        }
    }
}
