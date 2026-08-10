plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.dokka)
}

group = providers.gradleProperty("pomGroupId").get()
version = providers.gradleProperty("versionName").get()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

// kotlin-compile-testing (kctfork) — used only by the test suite — calls compiler-embeddable APIs
// gated behind this opt-in; scoped to test compilation rather than annotating every test file.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.moxy)

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.asm)
    testImplementation(libs.asm.util)
}

tasks.test {
    // JUnit4 (matches the original moxy-compiler test suite's style) — default runner, no vintage/platform needed.
    testLogging {
        events("passed", "skipped", "failed")
    }

    dependsOn(tasks.jar)
    val processorClasspath = files(tasks.jar) + configurations.runtimeClasspath.get()
    systemProperty("moxyKsp.processorClasspath", processorClasspath.asPath)
    systemProperty("moxyKsp.runtimeClasspath", configurations.runtimeClasspath.get().asPath)
    systemProperty("moxyKsp.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("moxyKsp.kspVersion", libs.versions.ksp.get())
}

mavenPublishing {
    coordinates(artifactId = "moxy-ksp")

    publishToMavenCentral()
    signAllPublications()

    pom {
        val pomDescription = project.property("pomDescription") as String
        val pomUrl = project.property("pomUrl") as String
        val pomScmUrl = project.property("pomScmUrl") as String
        val pomScmConnection = project.property("pomScmConnection") as String
        val pomScmDevConnection = project.property("pomScmDevConnection") as String
        val pomLicenseName = project.property("pomLicenseName") as String
        val pomLicenseUrl = project.property("pomLicenseUrl") as String
        val pomLicenseDist = project.property("pomLicenseDist") as String
        val pomDeveloperId = project.property("pomDeveloperId") as String
        val pomDeveloperName = project.property("pomDeveloperName") as String

        name.set("Moxy-Ksp")
        description.set(pomDescription)
        url.set(pomUrl)

        scm {
            url.set(pomScmUrl)
            connection.set(pomScmConnection)
            developerConnection.set(pomScmDevConnection)
        }
        licenses {
            license {
                name.set(pomLicenseName)
                url.set(pomLicenseUrl)
                distribution.set(pomLicenseDist)
            }
        }
        developers {
            developer {
                id.set(pomDeveloperId)
                name.set(pomDeveloperName)
            }
        }
    }
}
