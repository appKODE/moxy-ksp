package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import org.junit.Test

class ViewStateProviderTest {

    @Test
    fun `generates ViewStateProvider for a basic InjectViewState presenter`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                interface SampleView : MvpView {
                    fun showMessage(message: String)
                }

                @InjectViewState
                class SamplePresenter : MvpPresenter<SampleView>()
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val providerClass = result.classLoader.loadClass("sample.SamplePresenter\$\$ViewStateProvider")
        val provider = providerClass.getDeclaredConstructor().newInstance()
        val viewState = providerClass.getMethod("getViewState").invoke(provider)

        assertThat(viewState.javaClass.name).isEqualTo("sample.SampleView\$\$State")
    }

    @Test
    fun `MoxyReflector getViewState unwraps the ViewStateProvider to the actual ViewState`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                interface SampleView : MvpView {
                    fun showMessage(message: String)
                }

                @InjectViewState
                class SamplePresenter : MvpPresenter<SampleView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val moxyReflectorClass = result.classLoader.loadClass("com.arellomobile.mvp.MoxyReflector")
        val presenterClass = result.classLoader.loadClass("sample.SamplePresenter")
        val viewState = moxyReflectorClass.getMethod("getViewState", Class::class.java).invoke(null, presenterClass)

        // Regression guard: an earlier version returned the raw ViewStateProvider instance directly
        // (`sViewStateProviders[presenterClass]`) instead of calling `.getViewState()` on it — not
        // assignable to the view interface at all, breaking every real presenter/view attach.
        assertThat(viewState.javaClass.name).isEqualTo("sample.SampleView\$\$State")
    }

    @Test
    fun `throws at runtime when presenter has no resolvable view`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                @InjectViewState
                open class RawPresenter<V : MvpView> : MvpPresenter<V>()
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val providerClass = result.classLoader.loadClass("sample.RawPresenter\$\$ViewStateProvider")
        val provider = providerClass.getDeclaredConstructor().newInstance()
        val getViewState = providerClass.getMethod("getViewState")

        val thrown = runCatching { getViewState.invoke(provider) }.exceptionOrNull()
        assertThat(thrown).isNotNull()
    }
}
