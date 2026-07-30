package com.arellomobile.mvp.ksp.reflector

import com.arellomobile.mvp.MvpProcessor
import com.arellomobile.mvp.ViewStateProvider
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

private const val MOXY_REFLECTOR_DEFAULT_PACKAGE = "com.arellomobile.mvp"
private const val MOXY_REFLECTOR_SIMPLE_NAME = "MoxyReflector"

/**
 * Port of `com.arellomobile.mvp.compiler.reflector.MoxyReflectorGenerator` (built directly from
 * `origin/release/1.5.6`, which already fixes the two-line bug present on that branch's own earlier
 * history — the custom-package branch's `getViewStateProviders`/`getPresenterBinders` bodies here
 * already return the correct fields).
 *
 * `MoxyReflector` is generated as a Kotlin `class` (not `object`) with a `companion object` holding
 * `@JvmStatic` accessors: the runtime (`MvpProcessor.hasMoxyReflector()`, unchanged Java in the
 * `moxy` artifact) does `new MoxyReflector()` for an existence probe and calls its methods as plain
 * static Java calls — an `object`'s singleton-accessor shape would not satisfy either of those.
 */
object MoxyReflectorGenerator {
    private val CLASS_STAR = Class::class.asClassName().parameterizedBy(STAR)
    private val MAP_CLASS_TO_ANY = Map::class.asClassName().parameterizedBy(CLASS_STAR, ANY)
    private val MAP_CLASS_TO_LIST_OF_ANY = Map::class.asClassName().parameterizedBy(CLASS_STAR, List::class.asClassName().parameterizedBy(ANY))

    fun generate(
        destinationPackage: String,
        presenterDecls: List<KSClassDeclaration>,
        presentersContainers: List<KSClassDeclaration>,
        containerSuperclassChains: Map<KSClassDeclaration, List<KSClassDeclaration>>,
        strategyDecls: List<KSClassDeclaration>,
        additionalMoxyReflectorPackages: List<String>,
    ): FileSpec {
        val sortedPresenters = presenterDecls.sortedBy { it.qualifiedName?.asString() }
        val sortedStrategies = strategyDecls.sortedBy { it.qualifiedName?.asString() }
        val sortedAdditionalPackages = additionalMoxyReflectorPackages.sorted()
        val presenterBinders = groupPresenterBindersByRoot(presentersContainers, containerSuperclassChains)

        val companionBuilder = TypeSpec.companionObjectBuilder()
            .addProperty(viewStateProvidersProperty(sortedPresenters, sortedAdditionalPackages))
            .addProperty(presenterBindersProperty(presenterBinders, sortedAdditionalPackages))
            .addProperty(strategiesProperty(sortedStrategies, sortedAdditionalPackages))

        if (destinationPackage == MOXY_REFLECTOR_DEFAULT_PACKAGE) {
            companionBuilder
                .addFunction(getViewStateFunction())
                .addFunction(getPresenterBindersFunction())
                .addFunction(getStrategyFunction())
        } else {
            companionBuilder
                .addFunction(getViewStateProvidersFunction())
                .addFunction(getPresenterBindersMapFunction())
                .addFunction(getStrategiesFunction())
        }

        val classSpec = TypeSpec.classBuilder(MOXY_REFLECTOR_SIMPLE_NAME)
            .addType(companionBuilder.build())
            .build()

        return FileSpec.builder(destinationPackage, MOXY_REFLECTOR_SIMPLE_NAME)
            .addType(classSpec)
            .build()
    }

    private fun viewStateProvidersProperty(presenters: List<KSClassDeclaration>, additionalPackages: List<String>): PropertySpec {
        val initializer = CodeBlock.builder().beginControlFlow("mutableMapOf<%T, %T>().apply", CLASS_STAR, ANY)
        for (presenter in presenters) {
            val presenterClassName = presenter.toClassName()
            val providerClassName = ClassName(
                presenterClassName.packageName,
                presenterClassName.simpleNames.joinToString("$") + MvpProcessor.VIEW_STATE_PROVIDER_SUFFIX,
            )
            initializer.addStatement("put(%T::class.java, %T())", presenterClassName, providerClassName)
        }
        for (pkg in additionalPackages) {
            initializer.addStatement("putAll(%T.getViewStateProviders())", ClassName(pkg, MOXY_REFLECTOR_SIMPLE_NAME))
        }
        initializer.endControlFlow()

        return PropertySpec.builder("sViewStateProviders", MAP_CLASS_TO_ANY, KModifier.PRIVATE)
            .initializer(initializer.build())
            .build()
    }

    private fun presenterBindersProperty(presenterBinders: Map<KSClassDeclaration, List<KSClassDeclaration>>, additionalPackages: List<String>): PropertySpec {
        val listOfAny = List::class.asClassName().parameterizedBy(ANY)
        val initializer = CodeBlock.builder().beginControlFlow("mutableMapOf<%T, %T>().apply", CLASS_STAR, listOfAny)
        for ((container, binders) in presenterBinders) {
            val binderConstructorCalls = CodeBlock.builder()
            binders.forEachIndexed { index, binder ->
                if (index > 0) binderConstructorCalls.add(", ")
                val binderClassName = binder.toClassName()
                val generatedBinderClassName = ClassName(
                    binderClassName.packageName,
                    binderClassName.simpleNames.joinToString("$") + MvpProcessor.PRESENTER_BINDER_SUFFIX,
                )
                binderConstructorCalls.add("%T()", generatedBinderClassName)
            }
            initializer.add("put(%T::class.java, listOf(", container.toClassName())
            initializer.add(binderConstructorCalls.build())
            initializer.add("))\n")
        }
        for (pkg in additionalPackages) {
            initializer.addStatement("putAll(%T.getPresenterBinders())", ClassName(pkg, MOXY_REFLECTOR_SIMPLE_NAME))
        }
        initializer.endControlFlow()

        return PropertySpec.builder("sPresenterBinders", MAP_CLASS_TO_LIST_OF_ANY, KModifier.PRIVATE)
            .initializer(initializer.build())
            .build()
    }

    private fun strategiesProperty(strategies: List<KSClassDeclaration>, additionalPackages: List<String>): PropertySpec {
        val initializer = CodeBlock.builder().beginControlFlow("mutableMapOf<%T, %T>().apply", CLASS_STAR, ANY)
        for (strategy in strategies) {
            val strategyClassName = strategy.toClassName()
            initializer.addStatement("put(%T::class.java, %T())", strategyClassName, strategyClassName)
        }
        for (pkg in additionalPackages) {
            initializer.addStatement("putAll(%T.getStrategies())", ClassName(pkg, MOXY_REFLECTOR_SIMPLE_NAME))
        }
        initializer.endControlFlow()

        return PropertySpec.builder("sStrategies", MAP_CLASS_TO_ANY, KModifier.PRIVATE)
            .initializer(initializer.build())
            .build()
    }

    private fun getViewStateFunction(): FunSpec =
        FunSpec.builder("getViewState")
            .addAnnotation(JvmStatic::class)
            .addParameter("presenterClass", CLASS_STAR)
            .returns(ANY.copy(nullable = true))
            .addStatement(
                "val viewStateProvider = sViewStateProviders[presenterClass] as? %T ?: return null",
                ViewStateProvider::class,
            )
            .addStatement("return viewStateProvider.getViewState()")
            .build()

    private fun getPresenterBindersFunction(): FunSpec =
        FunSpec.builder("getPresenterBinders")
            .addAnnotation(JvmStatic::class)
            .addParameter("delegated", CLASS_STAR)
            .returns(List::class.asClassName().parameterizedBy(ANY).copy(nullable = true))
            .addStatement("return sPresenterBinders[delegated]")
            .build()

    private fun getStrategyFunction(): FunSpec =
        FunSpec.builder("getStrategy")
            .addAnnotation(JvmStatic::class)
            .addParameter("strategyClass", CLASS_STAR)
            .returns(ANY.copy(nullable = true))
            .addStatement("return sStrategies[strategyClass]")
            .build()

    private fun getViewStateProvidersFunction(): FunSpec =
        FunSpec.builder("getViewStateProviders")
            .addAnnotation(JvmStatic::class)
            .returns(MAP_CLASS_TO_ANY)
            .addStatement("return sViewStateProviders")
            .build()

    private fun getPresenterBindersMapFunction(): FunSpec =
        FunSpec.builder("getPresenterBinders")
            .addAnnotation(JvmStatic::class)
            .returns(MAP_CLASS_TO_LIST_OF_ANY)
            .addStatement("return sPresenterBinders")
            .build()

    private fun getStrategiesFunction(): FunSpec =
        FunSpec.builder("getStrategies")
            .addAnnotation(JvmStatic::class)
            .returns(MAP_CLASS_TO_ANY)
            .addStatement("return sStrategies")
            .build()

    /**
     * Collects presenter binders from superclasses that are also presenter containers, using
     * superclass chains precomputed by `InjectPresenterProcessor` while each container's round was
     * still fresh — calling `KSType`/`KSTypeReference.resolve()` again here (this runs from
     * `SymbolProcessor.finish()`, after every round has completed) throws
     * `KaInvalidLifetimeOwnerAccessException` under KSP2.
     */
    private fun groupPresenterBindersByRoot(
        presentersContainers: List<KSClassDeclaration>,
        containerSuperclassChains: Map<KSClassDeclaration, List<KSClassDeclaration>>,
    ): Map<KSClassDeclaration, List<KSClassDeclaration>> {
        val extendingMap = HashMap<KSClassDeclaration, KSClassDeclaration?>()
        for (container in presentersContainers) {
            val chain = containerSuperclassChains[container].orEmpty()
            val parent = chain.firstOrNull { superclass ->
                presentersContainers.any { it.qualifiedName?.asString() == superclass.qualifiedName?.asString() }
            }
            extendingMap[container] = parent
        }

        val result = LinkedHashMap<KSClassDeclaration, List<KSClassDeclaration>>()
        for (container in presentersContainers.sortedBy { it.qualifiedName?.asString() }) {
            val chain = mutableListOf(container)
            var key: KSClassDeclaration? = container
            while (true) {
                key = extendingMap[key] ?: break
                chain.add(key)
            }
            result[container] = chain
        }
        return result
    }
}
