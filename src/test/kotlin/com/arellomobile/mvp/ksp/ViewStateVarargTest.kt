package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import org.junit.Test

/**
 * Regression coverage for vararg view methods (see [com.arellomobile.mvp.ksp.viewstate.ViewMethod]'s
 * `varargElement()`). Before that fix, the generator inferred vararg-ness from the *shape* of the
 * resolved parameter type instead of `KSValueParameter.isVararg`, producing a plain `Array<Any>`
 * override parameter — never a real Kotlin `vararg`. That's wrong for three independent reasons,
 * each covered by one fixture below:
 *
 * 1. A Java `Object... formatArgs` source (this codebase's actual shape) generated a non-vararg
 *    override, so Kotlin callers had to build an array by hand instead of using vararg call syntax.
 * 2. A Kotlin-declared `vararg messages: String` method couldn't be overridden at all — Kotlin
 *    requires an override's vararg-ness to match, so the old generator's plain-array parameter was a
 *    flat compile error. Nobody hit this only because every vararg view method in practice happens to
 *    be Java-declared.
 * 3. Overload resolution/dedup (by method name, see `ViewInterfaceProcessor`'s `uniqueSuffix`
 *    counter) must keep working once one of the overloads is a vararg.
 *
 * Generic substitution through `KSFunctionDeclaration.asMemberOf` (already covered for non-vararg
 * parameters by `ViewStateTest`) is re-verified here specifically for a vararg parameter, since
 * `varargElement()` runs on the *resolved* (substituted) type, not the raw declared type — if it ran
 * on the wrong one, `T` would leak through unsubstituted instead of resolving to `String`.
 */
class ViewStateVarargTest {

    @Test
    fun `Java Object vararg source generates a real Kotlin vararg override`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.java(
                "JavaView.java",
                """
                package sample;

                import com.arellomobile.mvp.MvpView;

                public interface JavaView extends MvpView {
                    void showMessage(int label, Object... formatArgs);
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

        val viewStateClass = result.classLoader.loadClass("sample.JavaView\$\$State")
        val method = viewStateClass.getMethod("showMessage", Int::class.javaPrimitiveType, Array<Any?>::class.java)
        assertThat(method.isVarArgs).isTrue()

        val commandClass = result.classLoader.loadClass("sample.JavaView\$\$State\$ShowMessageCommand")
        val constructor = commandClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Array<Any?>::class.java)
        assertThat(constructor.isVarArgs).isTrue()

        // Real vararg call syntax at the JVM level: pass individual elements, not a pre-built array.
        val command = constructor.newInstance(1, arrayOf<Any?>("a", "b"))
        val stored = commandClass.getMethod("getFormatArgs").invoke(command) as Array<*>
        assertThat(stored).asList().containsExactly("a", "b").inOrder()
    }

    @Test
    fun `Kotlin-declared vararg method overrides correctly`() {
        // Pre-fix this was a flat COMPILATION_ERROR: overriding `vararg messages: String` with a
        // plain (non-vararg) `Array<String>` parameter is illegal in Kotlin.
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                interface SampleView : MvpView {
                    fun show(vararg messages: String)
                }

                @InjectViewState
                class SamplePresenter : MvpPresenter<SampleView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val viewStateClass = result.classLoader.loadClass("sample.SampleView\$\$State")
        val method = viewStateClass.getMethod("show", Array<String>::class.java)
        assertThat(method.isVarArgs).isTrue()
    }

    @Test
    fun `generic vararg element type is substituted via asMemberOf, not left as the type variable`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                interface GenericView<T> : MvpView {
                    fun show(vararg items: T)
                }

                interface StringView : GenericView<String>

                @InjectViewState
                class SamplePresenter : MvpPresenter<StringView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val viewStateClass = result.classLoader.loadClass("sample.StringView\$\$State")
        // If varargElement() had unwrapped the *unsubstituted* declared type instead of the
        // asMemberOf-resolved one, this would resolve to Array<Any?>, not Array<String>.
        val method = viewStateClass.getMethod("show", Array<String>::class.java)
        assertThat(method.isVarArgs).isTrue()
    }

    @Test
    fun `vararg overload alongside a non-vararg overload of the same name both survive dedup`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.InjectViewState
                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView

                interface SampleView : MvpView {
                    fun show(message: String)
                    fun show(vararg messages: String)
                }

                @InjectViewState
                class SamplePresenter : MvpPresenter<SampleView>()
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val viewStateClass = result.classLoader.loadClass("sample.SampleView\$\$State")
        val plainMethod = viewStateClass.getMethod("show", String::class.java)
        assertThat(plainMethod.isVarArgs).isFalse()
        val varargMethod = viewStateClass.getMethod("show", Array<String>::class.java)
        assertThat(varargMethod.isVarArgs).isTrue()

        // ViewInterfaceProcessor's uniqueSuffix counter names repeats "Show", "Show1", ... in
        // declaration order — the plain overload is declared first.
        result.classLoader.loadClass("sample.SampleView\$\$State\$ShowCommand")
        result.classLoader.loadClass("sample.SampleView\$\$State\$Show1Command")
    }
}
