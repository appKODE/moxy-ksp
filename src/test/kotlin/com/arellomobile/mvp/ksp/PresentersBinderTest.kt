package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.junit.Test

/**
 * Verifies generated `$$PresentersBinder` wiring (raw class / tag / presenter-id constructor args,
 * and which construction path — default constructor vs. `@ProvidePresenter` method — was chosen)
 * via [PresenterField]'s plain getters and generated-source inspection, rather than by actually
 * constructing an `MvpPresenter` subclass through it. Any real `MvpPresenter()` construction in this
 * harness hits the same cross-classloader wall documented on [ViewStateTest]: its constructor chain
 * needs `MoxyReflector`, which is only visible from this test compilation's own child classloader,
 * not from `moxy.jar`'s (parent/system) classloader that `MvpPresenter` itself loads from.
 */
class PresentersBinderTest {

    @Test
    fun `binder wires field type, tag, and presenter type into the generated PresenterField`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView
                import com.arellomobile.mvp.presenter.InjectPresenter

                interface SampleView : MvpView

                class SamplePresenter : MvpPresenter<SampleView>()

                class Container : SampleView {
                    @InjectPresenter
                    lateinit var presenter: SamplePresenter
                }
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val binderClass = result.classLoader.loadClass("sample.Container\$\$PresentersBinder")
        val binder = binderClass.getDeclaredConstructor().newInstance()
        val fields = binderClass.getMethod("getPresenterFields").invoke(binder) as List<*>
        assertThat(fields).hasSize(1)

        val presenterFieldClass = fields[0]!!.javaClass
        val containerClass = result.classLoader.loadClass("sample.Container")
        val container = containerClass.getDeclaredConstructor().newInstance()

        val presenterClass = presenterFieldClass.getMethod("getPresenterClass").invoke(fields[0]) as Class<*>
        assertThat(presenterClass.name).isEqualTo("sample.SamplePresenter")

        val tag = presenterFieldClass.getMethod("getTag", Any::class.java).invoke(fields[0], container) as String
        assertThat(tag).isEqualTo("presenter")
    }

    @Test
    fun `parameterized presenter field type is stripped to raw class for the binder`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView
                import com.arellomobile.mvp.presenter.InjectPresenter

                interface SampleView : MvpView

                class GenericPresenter<T> : MvpPresenter<SampleView>()

                class Container : SampleView {
                    @InjectPresenter
                    lateinit var presenter: GenericPresenter<String>
                }
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val binderClass = result.classLoader.loadClass("sample.Container\$\$PresentersBinder")
        val binder = binderClass.getDeclaredConstructor().newInstance()
        val fields = binderClass.getMethod("getPresenterFields").invoke(binder) as List<*>

        val presenterClass = fields[0]!!.javaClass.getMethod("getPresenterClass").invoke(fields[0]) as Class<*>
        assertThat(presenterClass.name).isEqualTo("sample.GenericPresenter")
    }

    @Test
    fun `ProvidePresenter method is used instead of the default constructor`() {
        val compilation = KotlinCompilation().apply {
            sources = listOf(
                CompileTestHelper.kotlin(
                    "Sample.kt",
                    """
                    package sample

                    import com.arellomobile.mvp.MvpPresenter
                    import com.arellomobile.mvp.MvpView
                    import com.arellomobile.mvp.presenter.InjectPresenter
                    import com.arellomobile.mvp.presenter.ProvidePresenter

                    interface SampleView : MvpView

                    class SamplePresenter(val label: String) : MvpPresenter<SampleView>()

                    class Container : SampleView {
                        @InjectPresenter
                        lateinit var presenter: SamplePresenter

                        @ProvidePresenter
                        fun providePresenter(): SamplePresenter = SamplePresenter("provided")
                    }
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            messageOutputStream = System.out
        }
        compilation.configureKsp {
            symbolProcessorProviders.add(MoxyKspProcessorProvider())
            withCompilation = true
        }
        val result = compilation.compile()
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val binderSource = compilation.kspSourcesDir.walkTopDown()
            .first { it.name.contains("PresentersBinder") && it.name.endsWith(".kt") }
            .readText()

        // The generated providePresenter() body must call the container's own @ProvidePresenter
        // method, not construct SamplePresenter directly.
        assertThat(binderSource).contains("delegated.providePresenter()")
        assertThat(binderSource).doesNotContain("SamplePresenter()")
    }

    @Test
    fun `presenter binders aggregate across a container inheritance chain`() {
        // Regression test for a KSP2-only crash: MoxyReflectorGenerator.groupPresenterBindersByRoot
        // runs from SymbolProcessor.finish(), after every processing round has completed. It used to
        // walk raw KSClassDeclaration objects retained from earlier rounds; under KSP2 any lazy
        // accessor on those (even .qualifiedName, even equals/hashCode) throws
        // KaInvalidLifetimeOwnerAccessException ("PSI has changed since creation") once the round
        // that created them has ended. The fix converts them to plain KotlinPoet ClassName values at
        // collection time. This container-inheritance shape is what actually exercises that grouping
        // code — without it, groupPresenterBindersByRoot's chain-walking logic (and this bug) go
        // completely untested.
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView
                import com.arellomobile.mvp.presenter.InjectPresenter

                interface SampleView : MvpView

                class BasePresenter : MvpPresenter<SampleView>()
                class ChildPresenter : MvpPresenter<SampleView>()

                open class BaseContainer : SampleView {
                    @InjectPresenter
                    lateinit var basePresenter: BasePresenter
                }

                class ChildContainer : BaseContainer() {
                    @InjectPresenter
                    lateinit var childPresenter: ChildPresenter
                }
                """.trimIndent(),
            ),
        )
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val reflector = result.classLoader.loadClass("com.arellomobile.mvp.MoxyReflector")
        val childContainerClass = result.classLoader.loadClass("sample.ChildContainer")

        val binders = reflector.getMethod("getPresenterBinders", Class::class.java)
            .invoke(null, childContainerClass) as List<*>

        val binderClassNames = binders.map { it!!.javaClass.name }
        assertThat(binderClassNames).containsExactly(
            "sample.ChildContainer\$\$PresentersBinder",
            "sample.BaseContainer\$\$PresentersBinder",
        )
    }

    @Test
    fun `InjectPresenter on a non-View container is a compile error`() {
        val result = CompileTestHelper.compile(
            CompileTestHelper.kotlin(
                "Sample.kt",
                """
                package sample

                import com.arellomobile.mvp.MvpPresenter
                import com.arellomobile.mvp.MvpView
                import com.arellomobile.mvp.presenter.InjectPresenter

                interface SampleView : MvpView

                class SamplePresenter : MvpPresenter<SampleView>()

                class NotAView {
                    @InjectPresenter
                    lateinit var presenter: SamplePresenter
                }
                """.trimIndent(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    }
}
