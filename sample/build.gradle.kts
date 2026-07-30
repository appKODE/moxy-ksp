plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("sample.MainKt")
}

dependencies {
    implementation(libs.moxy)
    // Real external-consumer usage: resolved from mavenLocal() (see ../settings.gradle.kts), the
    // same way a real project would after `./gradlew publishToMavenLocal` — not a project() shortcut.
    ksp("${providers.gradleProperty("pomGroupId").get()}:moxy-ksp:${providers.gradleProperty("versionName").get()}")

    // Strategy behavior tests run here (not in the root module's test suite) specifically because
    // this is an ordinary Gradle module with KSP genuinely applied — no cross-classloader isolation
    // between the freshly-generated MoxyReflector and the moxy runtime jar's ViewCommands, unlike
    // the kotlin-compile-testing-based unit tests.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
