package com.arellomobile.mvp.ksp

import com.arellomobile.mvp.ksp.presenterbinder.InjectPresenterProcessor
import com.arellomobile.mvp.ksp.presenterbinder.PresenterBinderClassGenerator
import com.arellomobile.mvp.ksp.reflector.MoxyReflectorGenerator
import com.arellomobile.mvp.ksp.viewstate.ViewInterfaceProcessor
import com.arellomobile.mvp.ksp.viewstate.ViewStateClassGenerator
import com.arellomobile.mvp.ksp.KspUtil.superInterfaces
import com.arellomobile.mvp.ksp.viewstateprovider.InjectViewStateProcessor
import com.arellomobile.mvp.ksp.viewstateprovider.ViewStateProviderClassGenerator
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

private const val INJECT_PRESENTER = "com.arellomobile.mvp.presenter.InjectPresenter"
private const val INJECT_VIEW_STATE = "com.arellomobile.mvp.InjectViewState"
private const val REGISTER_MOXY_REFLECTOR_PACKAGES = "com.arellomobile.mvp.RegisterMoxyReflectorPackages"
private const val OPTION_MOXY_REFLECTOR_PACKAGE = "moxyReflectorPackage"
private const val MOXY_REFLECTOR_DEFAULT_PACKAGE = "com.arellomobile.mvp"

/**
 * Port of `com.arellomobile.mvp.compiler.MvpCompiler` for KSP.
 *
 * Dispatch order mirrors `MvpCompiler.throwableProcess()`: validate `@InjectPresenter` fields,
 * process `@InjectViewState` presenters, process `@InjectPresenter` containers, then process every
 * view interface discovered along the way. Unlike the apt original — which regenerates
 * `MoxyReflector` at the end of every annotation-processing round — this processor accumulates
 * registrations across all rounds and emits `MoxyReflector` once from [finish], KSP's idiomatic
 * single emission point. For the compiler's actual single-round common case this is byte-identical
 * to the original; it only differs (more correctly) in a genuine multi-round scenario the apt
 * compiler never robustly handled either.
 */
class MoxyKspProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private val injectViewStateProcessor = InjectViewStateProcessor()
    private val injectPresenterProcessor = InjectPresenterProcessor()

    private val processedPresenters = HashSet<String>()
    private val processedContainers = HashSet<String>()
    private val processedViews = HashSet<String>()
    private val additionalMoxyReflectorPackages = LinkedHashSet<String>()

    // ClassName, not KSClassDeclaration: read from finish(), after every round's KSP2 Analysis-API
    // session has ended (see InjectPresenterProcessor/InjectViewStateProcessor for the same pattern).
    private val usedStrategiesAccumulator = LinkedHashSet<ClassName>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        validateInjectPresenterFields(resolver)

        deferred += processInjectViewStatePresenters(resolver)
        deferred += processInjectPresenterContainers(resolver)
        deferred += processViewInterfaces(resolver)

        collectAdditionalMoxyReflectorPackages(resolver)

        return deferred
    }

    private fun validateInjectPresenterFields(resolver: Resolver) {
        for (annotated in resolver.getSymbolsWithAnnotation(INJECT_PRESENTER)) {
            val field = annotated as? KSPropertyDeclaration ?: continue
            InjectPresenterValidation.validate(field, logger)
        }
    }

    private fun processInjectViewStatePresenters(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        for (annotated in resolver.getSymbolsWithAnnotation(INJECT_VIEW_STATE)) {
            val presenterDecl = annotated as? KSClassDeclaration ?: continue
            val qualifiedName = presenterDecl.qualifiedName?.asString() ?: continue
            if (qualifiedName in processedPresenters) continue

            if (!presenterDecl.validate()) {
                deferred += presenterDecl
                continue
            }

            processedPresenters += qualifiedName

            val presenterInfo = injectViewStateProcessor.process(presenterDecl)
            val fileSpec = ViewStateProviderClassGenerator.generate(presenterInfo)
            val originatingFiles = listOfNotNull(presenterInfo.containingFile)
            fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = originatingFiles)
        }

        return deferred
    }

    private fun processInjectPresenterContainers(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        for (annotated in resolver.getSymbolsWithAnnotation(INJECT_PRESENTER)) {
            val field = annotated as? KSPropertyDeclaration ?: continue
            val container = field.parentDeclaration as? KSClassDeclaration ?: continue
            val qualifiedName = container.qualifiedName?.asString() ?: continue
            if (qualifiedName in processedContainers) continue

            if (!container.validate()) {
                deferred += container
                continue
            }

            processedContainers += qualifiedName

            val targetClassInfo = injectPresenterProcessor.process(field) ?: continue
            val fileSpec = PresenterBinderClassGenerator.generate(targetClassInfo)
            val originatingFiles = listOfNotNull(container.containingFile)
            fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = originatingFiles)
        }

        return deferred
    }

    private fun processViewInterfaces(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        for (viewDecl in injectViewStateProcessor.getUsedViews()) {
            val qualifiedName = viewDecl.qualifiedName?.asString() ?: continue
            if (qualifiedName in processedViews) continue

            if (!viewDecl.validate()) {
                deferred += viewDecl
                continue
            }

            processedViews += qualifiedName

            val viewInterfaceProcessor = ViewInterfaceProcessor(resolver, logger)
            val viewInterfaceInfo = viewInterfaceProcessor.process(viewDecl)
            val fileSpec = ViewStateClassGenerator.generate(viewInterfaceInfo)

            val originatingFiles = buildSet<KSFile> {
                viewDecl.containingFile?.let { add(it) }
                addAll(transitiveSuperInterfaceFiles(viewDecl))
            }
            fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = originatingFiles)

            usedStrategiesAccumulator += viewInterfaceProcessor.getUsedStrategies().map { it.toClassName() }
        }

        return deferred
    }

    private fun transitiveSuperInterfaceFiles(decl: KSClassDeclaration): Set<KSFile> {
        val visited = LinkedHashSet<KSClassDeclaration>()
        val files = LinkedHashSet<KSFile>()
        fun visit(current: KSClassDeclaration) {
            if (!visited.add(current)) return
            for (superInterfaceRef in current.superInterfaces()) {
                val ifaceDecl = superInterfaceRef.resolve().declaration as? KSClassDeclaration ?: continue
                ifaceDecl.containingFile?.let { files.add(it) }
                visit(ifaceDecl)
            }
        }
        visit(decl)
        return files
    }

    private fun collectAdditionalMoxyReflectorPackages(resolver: Resolver) {
        for (annotated in resolver.getSymbolsWithAnnotation(REGISTER_MOXY_REFLECTOR_PACKAGES)) {
            val classDecl = annotated as? KSClassDeclaration ?: continue
            val annotation = KspUtil.findAnnotation(classDecl, REGISTER_MOXY_REFLECTOR_PACKAGES) ?: continue
            val packages = KspUtil.annotationValue(annotation, "value")?.value as? List<*> ?: continue
            packages.filterIsInstance<String>().forEach { additionalMoxyReflectorPackages.add(it) }
        }
    }

    override fun finish() {
        if (processedPresenters.isEmpty() && processedContainers.isEmpty() && usedStrategiesAccumulator.isEmpty() &&
            additionalMoxyReflectorPackages.isEmpty()
        ) {
            return
        }

        val destinationPackage = options[OPTION_MOXY_REFLECTOR_PACKAGE] ?: MOXY_REFLECTOR_DEFAULT_PACKAGE

        val fileSpec = MoxyReflectorGenerator.generate(
            destinationPackage = destinationPackage,
            presenterDecls = injectViewStateProcessor.getPresenterClassNames(),
            presentersContainers = injectPresenterProcessor.getPresentersContainers(),
            containerSuperclassChains = injectPresenterProcessor.getContainerSuperclassChains(),
            strategyDecls = usedStrategiesAccumulator.toList(),
            additionalMoxyReflectorPackages = additionalMoxyReflectorPackages.toList(),
        )

        fileSpec.writeTo(codeGenerator, aggregating = true)
    }
}

class MoxyKspProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        MoxyKspProcessor(environment.codeGenerator, environment.logger, environment.options)
}
