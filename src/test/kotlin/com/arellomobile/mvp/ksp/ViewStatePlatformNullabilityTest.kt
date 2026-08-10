package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import org.junit.Test

/**
 * Regression coverage for Java-declared view methods (see [com.arellomobile.mvp.ksp.viewstate.ViewMethod]'s
 * `platformAsNullable()`). Java parameters resolve to platform types; rendered as non-null Kotlin they
 * pick up an `Intrinsics.checkNotNullParameter`, so a Java caller passing null used to crash inside the
 * generated `$$State` rather than reaching the view. The apt original generated Java and never had that
 * check.
 */
class ViewStatePlatformNullabilityTest {

    @Test
    fun `null argument from a Java-declared view method does not throw`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.java(
                "JavaView.java",
                """
                package sample;

                import com.arellomobile.mvp.MvpView;

                public interface JavaView extends MvpView {
                    void showText(String text);
                }
                """.trimIndent(),
            ),
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter

                @InjectViewState
                class SamplePresenter : MvpPresenter<JavaView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        // The Command constructor carries the same intrinsic null check as the `$$State` override
        // itself (both take the parameter straight from `Param.type`), and reaching it doesn't drag
        // in the runtime's MoxyReflector lookup the way calling the override would.
        // Pre-fix: InvocationTargetException wrapping NullPointerException from that check.
        val commandClass = result.classLoader.loadClass("sample.JavaView\$\$State\$ShowTextCommand")
        assertThat(commandClass.getDeclaredConstructor(String::class.java).newInstance(null as String?)).isNotNull()
    }

    @Test
    fun `Kotlin-declared non-null parameter stays non-null`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                interface SampleView : MvpView {
                    fun showText(text: String)
                }

                @InjectViewState
                class SamplePresenter : MvpPresenter<SampleView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val commandClass = result.classLoader.loadClass("sample.SampleView\$\$State\$ShowTextCommand")
        val thrown = runCatching {
            commandClass.getDeclaredConstructor(String::class.java).newInstance(null as String?)
        }.exceptionOrNull()
        assertThat(thrown?.cause).isInstanceOf(NullPointerException::class.java)
    }
}
