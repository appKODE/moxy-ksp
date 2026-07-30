package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.configureKsp
import org.junit.Test

/**
 * Port of `MultiModulesTest`: `@RegisterMoxyReflectorPackages` aggregating a library module's
 * `MoxyReflector` (generated under a custom, non-default package) into an app module's own
 * `MoxyReflector`. This is exactly the branch where the apt original's `MoxyReflectorGenerator` had
 * a two-line bug on `master` (fixed on `origin/release/1.5.6`, the actual port target) — asserting
 * real aggregated behavior here, not just that it compiles, locks that in.
 */
class MultiModulesTest {

    @Test
    fun `RegisterMoxyReflectorPackages aggregates a library module's custom-package MoxyReflector`() {
        val libCompilation = KotlinCompilation().apply {
            sources = listOf(
                CompileTestHelper.kotlin(
                    "Lib.kt",
                    """
                    package lib

                    import com.arellomobile.mvp.InjectViewState
                    import com.arellomobile.mvp.MvpPresenter
                    import com.arellomobile.mvp.MvpView

                    interface LibView : MvpView {
                        fun showLib(message: String)
                    }

                    @InjectViewState
                    class LibPresenter : MvpPresenter<LibView>()
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            messageOutputStream = System.out
        }
        libCompilation.configureKsp {
            symbolProcessorProviders.add(MoxyKspProcessorProvider())
            processorOptions["moxyReflectorPackage"] = "lib"
            withCompilation = true
        }
        val libResult = libCompilation.compile()
        assertThat(libResult.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val libReflector = libResult.classLoader.loadClass("lib.MoxyReflector")
        val libViewStateProviders = libReflector.getMethod("getViewStateProviders").invoke(null) as Map<*, *>
        assertThat(libViewStateProviders).isNotEmpty()

        val appCompilation = KotlinCompilation().apply {
            sources = listOf(
                CompileTestHelper.kotlin(
                    "App.kt",
                    """
                    package app

                    import com.arellomobile.mvp.InjectViewState
                    import com.arellomobile.mvp.MvpPresenter
                    import com.arellomobile.mvp.MvpView
                    import com.arellomobile.mvp.RegisterMoxyReflectorPackages

                    interface AppView : MvpView {
                        fun showApp(message: String)
                    }

                    @InjectViewState
                    class AppPresenter : MvpPresenter<AppView>()

                    @RegisterMoxyReflectorPackages("lib")
                    class ReflectorAggregator
                    """.trimIndent(),
                ),
            )
            classpaths = listOf(libCompilation.classesDir)
            inheritClassPath = true
            messageOutputStream = System.out
        }
        appCompilation.configureKsp {
            symbolProcessorProviders.add(MoxyKspProcessorProvider())
            withCompilation = true
        }
        val appResult = appCompilation.compile()
        assertThat(appResult.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        // Default package (no moxyReflectorPackage option set for the app module).
        val appReflector = appResult.classLoader.loadClass("com.arellomobile.mvp.MoxyReflector")
        val libPresenterClass = appResult.classLoader.loadClass("lib.LibPresenter")

        val aggregatedProvider = appReflector.getMethod("getViewState", Class::class.java).invoke(null, libPresenterClass)
        assertThat(aggregatedProvider).isNotNull()
    }
}
