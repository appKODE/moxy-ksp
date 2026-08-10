package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A presenter or `@InjectPresenter` container is dropped from `MoxyReflector` — silently, with a
 * green build — if *any* member of it declares a type KSP cannot resolve in that round. The
 * processor used to run `KSAnnotated.validate()` over the whole class, and a class that never
 * validates is deferred every round and then forgotten when the rounds end.
 *
 * That is not a corner case in an Android app: `kspKotlin` runs before kapt, so every kapt-generated
 * type (Dagger components, and anything else a screen holds) is unresolved for the entire KSP run
 * and never becomes resolvable. The class compiles, ships, and then `MvpProcessor` finds no binder
 * for it and attaches nothing — surfacing as a `NullPointerException` on the first presenter call in
 * `onCreate`, on some screens and not others depending only on what they happen to declare.
 *
 * The fixture builds only `kspKotlin`, never `compileJava`/`compileKotlin`: that is exactly the
 * window kapt-generated sources do not yet exist in, and it lets the unresolved type stand in for
 * one without a kapt round in the fixture.
 */
class UnresolvedMemberTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `containers and presenters keep their registration despite an unresolvable member type`() {
        writeProject()

        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withArguments("kspKotlin", "--stacktrace")
            .forwardOutput()
            .build()

        val reflector = reflector()
        assertThat(reflector).contains("KotlinContainer::class.java")
        assertThat(reflector).contains("JavaContainer::class.java")
        assertThat(reflector).contains("SamplePresenter::class.java")
    }

    @Test
    fun `an unresolvable presenter field type fails the build instead of dropping the container`() {
        writeProject()
        // The one member the processor does read. Nothing correct can be generated from it, and the
        // pre-fix silent drop is what this whole test class exists to rule out.
        write(
            "src/main/kotlin/app/Broken.kt",
            """
            package app

            import com.arellomobile.mvp.presenter.InjectPresenter

            class BrokenContainer : SampleView {
                @InjectPresenter
                lateinit var presenter: MissingPresenter

                override fun show(message: String) = Unit
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withArguments("kspKotlin", "--stacktrace")
            .forwardOutput()
            .buildAndFail()

        assertThat(result.output).contains("app.BrokenContainer (@InjectPresenter container) at ")
        assertThat(result.output).contains("Broken.kt:5")
    }

    private fun reflector(): String =
        File(projectDir.root, "build/generated/ksp/main/kotlin/com/arellomobile/mvp/MoxyReflector.kt").readText()

    private fun writeProject() {
        val processorClasspath = fileListLiteral("moxyKsp.processorClasspath")
        val runtimeClasspath = fileListLiteral("moxyKsp.runtimeClasspath")

        write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "unresolved-member-fixture"
            """.trimIndent(),
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "${systemProperty("moxyKsp.kotlinVersion")}"
                id("com.google.devtools.ksp") version "${systemProperty("moxyKsp.kspVersion")}"
            }

            repositories { mavenCentral() }

            dependencies {
                implementation(files($runtimeClasspath))
                ksp(files($processorClasspath))
            }
            """.trimIndent(),
        )
        write(
            "src/main/kotlin/app/Sample.kt",
            """
            package app

            import com.arellomobile.mvp.InjectViewState
            import com.arellomobile.mvp.MvpPresenter
            import com.arellomobile.mvp.MvpView
            import com.arellomobile.mvp.presenter.InjectPresenter

            interface SampleView : MvpView {
                fun show(message: String)
            }

            @InjectViewState
            class SamplePresenter : MvpPresenter<SampleView>() {
                // Stands in for a kapt-generated type: unresolved for the whole KSP run.
                lateinit var component: DaggerSampleComponent
            }

            class KotlinContainer : SampleView {
                @InjectPresenter
                lateinit var presenter: SamplePresenter

                lateinit var component: DaggerSampleComponent

                override fun show(message: String) = Unit
            }
            """.trimIndent(),
        )
        write(
            "src/main/java/app/JavaContainer.java",
            """
            package app;

            import com.arellomobile.mvp.presenter.InjectPresenter;

            public class JavaContainer implements SampleView {
                @InjectPresenter
                public SamplePresenter presenter;

                public DaggerSampleComponent component;

                @Override
                public void show(String message) {
                }
            }
            """.trimIndent(),
        )
    }

    private fun write(path: String, contents: String) {
        val file = File(projectDir.root, path)
        file.parentFile.mkdirs()
        file.writeText(contents + "\n")
    }

    private fun fileListLiteral(property: String): String =
        systemProperty(property)
            .split(File.pathSeparator)
            .joinToString(", ") { "\"" + it.replace("\\", "\\\\") + "\"" }

    private fun systemProperty(name: String): String =
        checkNotNull(System.getProperty(name)) { "System property '$name' is not set; see tasks.test in build.gradle.kts" }
}
