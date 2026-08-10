package com.arellomobile.mvp.ksp

import com.google.common.truth.Truth.assertThat
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.junit.Test

/**
 * The all-KSP shape of the same question `UnresolvedMemberTest` asks about kapt: a container holds a
 * member whose type another *KSP* processor generates (Dagger-KSP being the everyday case). That type
 * is unresolved in round 1 and resolvable from round 2 on, which is exactly what deferring a symbol
 * from `process()` is supposed to handle — so this test pins down whether the round mechanism really
 * recovers the container, or whether it is dropped from `MoxyReflector` just the same.
 */
class OtherProcessorRoundTest {

    /** Stand-in for Dagger-KSP: emits `app.DaggerSampleComponent` in the first round only. */
    private class ComponentGeneratorProvider(
        private val fileName: String = "DaggerSampleComponent",
        private val contents: String = "package app\n\nclass DaggerSampleComponent\n",
    ) : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            object : SymbolProcessor {
                private var generated = false

                override fun process(resolver: Resolver): List<KSAnnotated> {
                    if (!generated) {
                        generated = true
                        environment.codeGenerator.createNewFile(Dependencies(false), "app", fileName)
                            .bufferedWriter()
                            .use { it.write(contents) }
                    }
                    return emptyList()
                }
            }
    }

    @Test
    fun `container referencing a type another KSP processor generates stays registered`() {
        val compilation = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "Sample.kt",
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
                    class SamplePresenter : MvpPresenter<SampleView>()

                    class KotlinContainer : SampleView {
                        @InjectPresenter
                        lateinit var presenter: SamplePresenter

                        lateinit var component: DaggerSampleComponent

                        override fun show(message: String) = Unit
                    }
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            messageOutputStream = System.out
        }
        compilation.configureKsp {
            symbolProcessorProviders.add(ComponentGeneratorProvider())
            symbolProcessorProviders.add(MoxyKspProcessorProvider())
            withCompilation = true
        }
        val result = compilation.compile()
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val reflector = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "MoxyReflector.kt" }
            .readText()
        assertThat(reflector).contains("KotlinContainer::class.java")
    }

    @Test
    fun `a view interface deferred to a later round still generates its state class`() {
        // The view is reachable only through the presenter, not through any annotation query, so it
        // is retried from the processor's own accumulated set rather than KSP's deferred list —
        // which means round 2 touches a KSClassDeclaration captured in round 1, the exact shape that
        // throws KaInvalidLifetimeOwnerAccessException under KSP2.
        val compilation = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "Sample.kt",
                    """
                    package app

                    import com.arellomobile.mvp.InjectViewState
                    import com.arellomobile.mvp.MvpPresenter
                    import com.arellomobile.mvp.MvpView

                    interface SampleView : MvpView {
                        fun show(payload: GeneratedPayload)
                    }

                    @InjectViewState
                    class SamplePresenter : MvpPresenter<SampleView>()
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            messageOutputStream = System.out
        }
        compilation.configureKsp {
            symbolProcessorProviders.add(
                ComponentGeneratorProvider(
                    fileName = "GeneratedPayload",
                    contents = "package app\n\nclass GeneratedPayload\n",
                ),
            )
            symbolProcessorProviders.add(MoxyKspProcessorProvider())
            withCompilation = true
        }
        val result = compilation.compile()
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val state = compilation.kspSourcesDir.walkTopDown().first { it.name == "SampleView\$\$State.kt" }.readText()
        assertThat(state).contains("GeneratedPayload")
    }

    @Test
    fun `deferring a container actually gets it back in a later round`() {
        // Here the unresolved type is the presenter itself, so round 1 *must* defer and round 2 must
        // recover it — the case that proves deferral works rather than being sidestepped by narrower
        // validation. It only works because the deferred symbol is the annotated field: KSP re-offers
        // deferred symbols through getSymbolsWithAnnotation(), which the container class, carrying no
        // annotation of its own, would never come back through.
        val compilation = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "Sample.kt",
                    """
                    package app

                    import com.arellomobile.mvp.MvpView
                    import com.arellomobile.mvp.presenter.InjectPresenter

                    interface SampleView : MvpView {
                        fun show(message: String)
                    }

                    class KotlinContainer : SampleView {
                        @InjectPresenter
                        lateinit var presenter: GeneratedPresenter

                        override fun show(message: String) = Unit
                    }
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            messageOutputStream = System.out
        }
        compilation.configureKsp {
            symbolProcessorProviders.add(
                ComponentGeneratorProvider(
                    fileName = "GeneratedPresenter",
                    contents = """
                    package app

                    import com.arellomobile.mvp.InjectViewState
                    import com.arellomobile.mvp.MvpPresenter

                    @InjectViewState
                    class GeneratedPresenter : MvpPresenter<SampleView>()

                    """.trimIndent(),
                ),
            )
            symbolProcessorProviders.add(MoxyKspProcessorProvider())
            withCompilation = true
        }
        val result = compilation.compile()
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

        val reflector = compilation.kspSourcesDir.walkTopDown()
            .first { it.name == "MoxyReflector.kt" }
            .readText()
        assertThat(reflector).contains("KotlinContainer::class.java")
        assertThat(reflector).contains("GeneratedPresenter::class.java")
    }
}
