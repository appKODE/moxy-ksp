package com.arellomobile.mvp.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp

/**
 * Shared compile+run helper for the test suite. Every fixture includes at least one Kotlin source
 * file alongside any Java ones — KSP only runs as part of Kotlin compilation, so a Java-only module
 * has no `kspKotlin` task to hang off at all (the accepted pure-Java-consumer gap, see the plan).
 * Mixing a trivial Kotlin file in with Java view/presenter sources here also exercises the exact
 * scenario the plan flagged as needing a real-build spike: it proves KSP sees Java-declared classes
 * as symbols too.
 */
object CompileTestHelper {

    fun compile(vararg sources: SourceFile): JvmCompilationResult {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            messageOutputStream = System.out
        }
        compilation.configureKsp {
            symbolProcessorProviders.add(MoxyKspProcessorProvider())
            // Without this, sources written from SymbolProcessor.finish() (MoxyReflector here) are
            // generated on disk but never make it into this same compilation's compiled output.
            withCompilation = true
        }
        return compilation.compile()
    }

    fun kotlin(name: String, contents: String): SourceFile = SourceFile.kotlin(name, contents)

    fun java(name: String, contents: String): SourceFile = SourceFile.java(name, contents)
}
