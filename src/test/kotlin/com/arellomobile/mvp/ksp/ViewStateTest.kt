package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import org.junit.Test

class ViewStateTest {

    /**
     * Verifies strategy wiring directly on the generated `ViewCommand` subclass
     * (`getStrategyType()`), rather than driving a full `attachView`/replay cycle through
     * `ViewCommands`/`MoxyReflector`. The latter needs `MoxyReflector` (freshly KSP-generated, living
     * only in this test compilation's own child classloader) to be visible from `ViewCommands`
     * (loaded from the pre-built `moxy` jar via the parent/system classloader, since moxy-ksp's own
     * main sources reference it) — a cross-classloader visibility gap the JVM never allows,
     * regardless of what this port generates. The original apt test suite sidesteps the same
     * constraint by never executing generated code at all, only bytecode-diffing it.
     */
    @Test
    fun `AddToEndStrategy is the default strategy wired into the generated command`() {
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

        val commandClass = result.classLoader.loadClass("sample.SampleView\$\$State\$ShowMessageCommand")
        val command = commandClass.getDeclaredConstructor(String::class.java).newInstance("hello")
        val strategyType = commandClass.getMethod("getStrategyType").invoke(command) as Class<*>

        assertThat(strategyType.name).isEqualTo("com.arellomobile.mvp.viewstate.strategy.AddToEndStrategy")
    }

    @Test
    fun `explicit StateStrategyType annotation is wired into the generated command`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView
                import com.arellomobile.mvp.viewstate.strategy.StateStrategyType
                import com.arellomobile.mvp.viewstate.strategy.SkipStrategy

                interface SampleView : MvpView {
                    @StateStrategyType(SkipStrategy::class)
                    fun showToast(message: String)
                }

                @InjectViewState
                class SamplePresenter : MvpPresenter<SampleView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val commandClass = result.classLoader.loadClass("sample.SampleView\$\$State\$ShowToastCommand")
        val command = commandClass.getDeclaredConstructor(String::class.java).newInstance("gone")
        val strategyType = commandClass.getMethod("getStrategyType").invoke(command) as Class<*>

        assertThat(strategyType.name).isEqualTo("com.arellomobile.mvp.viewstate.strategy.SkipStrategy")
    }

    @Test
    fun `generic view interface resolves inherited method parameter via Resolver_asMemberOf`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                interface GenericView<T> : MvpView {
                    fun showItem(item: T)
                }

                interface StringView : GenericView<String>

                @InjectViewState
                class SamplePresenter : MvpPresenter<StringView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val viewStateClass = result.classLoader.loadClass("sample.StringView\$\$State")
        // Confirms the inherited GenericView<T>.showItem parameter substituted T -> String, rather
        // than erasing to Any?/Object — the exact risk flagged for the star-projection simplification
        // in ViewMethod/ViewInterfaceProcessor.
        val method = viewStateClass.getMethod("showItem", String::class.java)
        assertThat(method).isNotNull()
    }

    @Test
    fun `conflicting strategy on independently-inherited interfaces is reported as a compile error`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView
                import com.arellomobile.mvp.viewstate.strategy.StateStrategyType
                import com.arellomobile.mvp.viewstate.strategy.SkipStrategy
                import com.arellomobile.mvp.viewstate.strategy.AddToEndSingleStrategy

                interface ViewA : MvpView {
                    @StateStrategyType(SkipStrategy::class)
                    fun show(message: String)
                }

                interface ViewB : MvpView {
                    @StateStrategyType(AddToEndSingleStrategy::class)
                    fun show(message: String)
                }

                interface CombinedView : ViewA, ViewB

                @InjectViewState
                class SamplePresenter : MvpPresenter<CombinedView>()
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    }
}
