package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `MoxyReflector` is a whole-module registry the runtime treats as authoritative — `MvpProcessor`
 * asks it for a class's binders and attaches nothing at all, silently, for a class that is missing
 * from it. Yet every `Resolver` root function a processor can read state from returns *dirty* files
 * only, so a reflector built from round state alone lists just what happened to be edited and every
 * untouched screen drops out of the registry until the next clean build.
 *
 * These run real multi-invocation Gradle builds (TestKit) because that is the only harness that
 * reproduces it: the `kotlin-compile-testing` suite deletes KSP's caches directory before every
 * compilation, so `rebuild` is always true there and the dirty-subset path is never taken.
 *
 * Each build here is a whole Gradle invocation, so the cases are grouped by which incremental input
 * change they exercise — modified, added, removed — rather than split one-per-method.
 */
class IncrementalReflectorTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    @Test
    fun `MoxyReflector stays complete when only one source file is rebuilt`() {
        writeProject()

        build()
        assertRegistersUntouchedFile(reflector())
        assertThat(reflector()).contains("TouchedPresenter::class.java")

        // Edit exactly one file; everything asserted below lives in the other one.
        touchedSource().appendText("\n// edited\n")
        build()

        assertSecondBuildWasIncremental()
        assertRegistersUntouchedFile(reflector())
        assertThat(reflector()).contains("TouchedPresenter::class.java")
    }

    @Test
    fun `MoxyReflector tracks sources added and removed between builds`() {
        writeProject()
        build()

        // Added file: the registry must gain it without losing anything already in it.
        write(
            "src/main/kotlin/app/Added.kt",
            """
            package app

            import com.arellomobile.mvp.InjectViewState
            import com.arellomobile.mvp.MvpPresenter
            import com.arellomobile.mvp.MvpView
            import com.arellomobile.mvp.presenter.InjectPresenter

            interface AddedView : MvpView {
                fun showAdded(message: String)
            }

            @InjectViewState
            class AddedPresenter : MvpPresenter<AddedView>()

            class AddedContainer : AddedView {
                @InjectPresenter
                lateinit var presenter: AddedPresenter

                override fun showAdded(message: String) = Unit
            }
            """.trimIndent(),
        )
        build()

        assertThat(dirtySetLog()).contains("src/main/kotlin/app/Added.kt")
        assertThat(reflector()).contains("AddedPresenter::class.java")
        assertThat(reflector()).contains("AddedContainer::class.java")
        assertThat(reflector()).contains("TouchedPresenter::class.java")
        assertRegistersUntouchedFile(reflector())

        // Removed file: its entries must go, everything else must stay, and — the part a grep over
        // the reflector alone would miss — the module must still compile, i.e. no generated output
        // of the deleted file survives to be referenced by the regenerated registry.
        assertThat(addedSource().delete()).isTrue()
        build(task = "build")

        assertThat(reflector()).doesNotContain("AddedPresenter::class.java")
        assertThat(reflector()).doesNotContain("AddedContainer::class.java")
        assertThat(reflector()).contains("TouchedPresenter::class.java")
        assertRegistersUntouchedFile(reflector())
    }

    @Test
    fun `MoxyReflector keeps a Java-declared container when only a Kotlin file is rebuilt`() {
        writeProject()

        build()
        assertThat(reflector()).contains("JavaContainer::class.java")

        touchedSource().appendText("\n// edited\n")
        build()

        assertSecondBuildWasIncremental()
        assertThat(reflector()).contains("JavaContainer::class.java")
    }

    /**
     * Everything declared in `Untouched.kt`: a presenter (view state provider), an
     * `@InjectPresenter` container (binder — the shape whose absence produced the reported
     * `lateinit property mPresenter has not been initialized` crash), and a non-default strategy,
     * which the processor accumulates from view interfaces and so loses by the same mechanism.
     */
    private fun assertRegistersUntouchedFile(reflector: String) {
        assertThat(reflector).contains("UntouchedPresenter::class.java")
        assertThat(reflector).contains("UntouchedContainer::class.java")
        assertThat(reflector).contains("SingleStateStrategy::class.java")
    }

    /**
     * Guards against a vacuous pass: had the build been a full rebuild rather than an incremental
     * one, the reflector would come out complete no matter what the processor does.
     */
    private fun assertSecondBuildWasIncremental() {
        assertThat(dirtySetLog()).contains("Modified\n  src/main/kotlin/app/Touched.kt")
    }

    private fun build(task: String = "kspKotlin") {
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            // `kspKotlin` is what the reflector assertions need; `build` additionally compiles it,
            // proving the regenerated registry and the surviving generated sources agree.
            // The Gradle property is passed on every invocation: changing it between builds is
            // itself a non-incremental input change, forcing the full rebuild these tests avoid.
            .withArguments(task, "-Pksp.incremental.log=true", "--stacktrace")
            .forwardOutput()
            .build()
    }

    private fun reflector(): String =
        File(projectDir.root, "build/generated/ksp/main/kotlin/com/arellomobile/mvp/MoxyReflector.kt").readText()

    private fun dirtySetLog(): String =
        File(projectDir.root, "build/kspCaches/main/logs/kspDirtySet.log").readText()

    private fun touchedSource(): File = File(projectDir.root, "src/main/kotlin/app/Touched.kt")

    private fun addedSource(): File = File(projectDir.root, "src/main/kotlin/app/Added.kt")

    private fun writeProject() {
        // The processor and the moxy runtime are handed over as plain file dependencies (paths
        // injected by the build, see `tasks.test` in build.gradle.kts) so this test always exercises
        // the working tree's processor without a publish step.
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
            rootProject.name = "incremental-reflector-fixture"
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
            "src/main/kotlin/app/Touched.kt",
            """
            package app

            import com.arellomobile.mvp.InjectViewState
            import com.arellomobile.mvp.MvpPresenter
            import com.arellomobile.mvp.MvpView

            interface TouchedView : MvpView {
                fun showTouched(message: String)
            }

            @InjectViewState
            class TouchedPresenter : MvpPresenter<TouchedView>()
            """.trimIndent(),
        )
        write(
            "src/main/kotlin/app/Untouched.kt",
            """
            package app

            import com.arellomobile.mvp.InjectViewState
            import com.arellomobile.mvp.MvpPresenter
            import com.arellomobile.mvp.MvpView
            import com.arellomobile.mvp.presenter.InjectPresenter
            import com.arellomobile.mvp.viewstate.strategy.SingleStateStrategy
            import com.arellomobile.mvp.viewstate.strategy.StateStrategyType

            interface UntouchedView : MvpView {
                @StateStrategyType(SingleStateStrategy::class)
                fun showUntouched(message: String)
            }

            @InjectViewState
            class UntouchedPresenter : MvpPresenter<UntouchedView>()

            class UntouchedContainer : UntouchedView {
                @InjectPresenter
                lateinit var presenter: UntouchedPresenter

                override fun showUntouched(message: String) = Unit
            }
            """.trimIndent(),
        )
        // The reported crash is in a Java Activity, and a Java source is a different incremental
        // input to KSP than a Kotlin one: it is never touched by these builds.
        write(
            "src/main/java/app/JavaContainer.java",
            """
            package app;

            import com.arellomobile.mvp.presenter.InjectPresenter;

            public class JavaContainer implements UntouchedView {
                @InjectPresenter
                public UntouchedPresenter presenter;

                @Override
                public void showUntouched(String message) {
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

    /** Renders a path-separated classpath as a Kotlin `"a", "b"` argument list for `files(...)`. */
    private fun fileListLiteral(property: String): String =
        systemProperty(property)
            .split(File.pathSeparator)
            .joinToString(", ") { "\"" + it.replace("\\", "\\\\") + "\"" }

    private fun systemProperty(name: String): String =
        checkNotNull(System.getProperty(name)) { "System property '$name' is not set; see tasks.test in build.gradle.kts" }
}
