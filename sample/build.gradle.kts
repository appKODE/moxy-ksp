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
    // Real external-consumer coordinate, kept so this module reads like an actual downstream
    // build.gradle.kts. Resolved to the root project via dependencySubstitution below (same
    // composite-build pattern as build-publish-plugin/example-project) instead of mavenLocal, so
    // CI doesn't need a publishToMavenLocal step before `./gradlew build test`.
    ksp("${providers.gradleProperty("pomGroupId").get()}:moxy-ksp:${providers.gradleProperty("versionName").get()}")

    // Strategy behavior tests run here (not in the root module's test suite) specifically because
    // this is an ordinary Gradle module with KSP genuinely applied — no cross-classloader isolation
    // between the freshly-generated MoxyReflector and the moxy runtime jar's ViewCommands, unlike
    // the kotlin-compile-testing-based unit tests.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

configurations.all {
    if (name == "kspKotlinProcessorClasspath") {
        resolutionStrategy.dependencySubstitution {
            substitute(module("${providers.gradleProperty("pomGroupId").get()}:moxy-ksp"))
                .using(project(":"))
        }
    }
}
