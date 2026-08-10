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
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
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
private const val MOXY_PRESENTER_PACKAGE = "com.arellomobile.mvp.presenter."

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

    // Qualified name -> what it was, for everything deferred and not (yet) processed. KSP hands
    // deferred symbols back every round and then simply forgets the leftovers, which for a registry
    // the runtime treats as authoritative means a screen that compiles and has no presenter at all.
    // Reported from finish() instead, where "still here" finally means "never processed".
    private val neverProcessed = LinkedHashMap<String, Deferral>()

    /** Both fields are read from [finish], so the location is rendered while the symbol is still alive. */
    private class Deferral(val kind: String, val location: String)

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

            // Supertypes only: the view type comes from `MvpPresenter<View>`, and a presenter's own
            // members (an @Inject field of a kapt-generated type, say) are never read here. A
            // whole-class validate() would defer — and then silently drop — the presenter over a
            // member this processor does not even look at.
            if (!presenterDecl.validate { parent, child -> parent !is KSClassDeclaration || child !is KSDeclaration }) {
                deferred += presenterDecl
                neverProcessed[qualifiedName] = deferral(presenterDecl, "@InjectViewState presenter")
                continue
            }

            processedPresenters += qualifiedName
            neverProcessed -= qualifiedName

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

            // Supertypes (the View check) and moxy-annotated members only — see the note on
            // presenters above; this is the shape that actually crashed at runtime, an Activity
            // holding one unrelated field of a type KSP cannot see yet.
            if (!container.validate { parent, child -> parent !is KSClassDeclaration || child !is KSDeclaration || child.isMoxyMember() }) {
                // The *field*, not the container: KSP re-offers deferred symbols through
                // getSymbolsWithAnnotation(), so deferring the unannotated container class meant it
                // never came back and one failed round dropped it for good.
                deferred += field
                neverProcessed[qualifiedName] = deferral(container, "@InjectPresenter container")
                continue
            }

            processedContainers += qualifiedName
            neverProcessed -= qualifiedName

            val targetClassInfo = injectPresenterProcessor.process(field) ?: continue
            val fileSpec = PresenterBinderClassGenerator.generate(targetClassInfo)
            val originatingFiles = listOfNotNull(container.containingFile)
            fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = originatingFiles)
        }

        return deferred
    }

    private fun processViewInterfaces(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        for (qualifiedName in injectViewStateProcessor.getUsedViews()) {
            if (qualifiedName in processedViews) continue

            // Resolved fresh every round: this set is carried across rounds by name precisely because
            // a declaration from an earlier round is dead under KSP2.
            val viewDecl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(qualifiedName))
                ?: continue

            // Views are validated whole: every method of the interface is generated into `$$State`.
            // Nothing is handed to KSP's deferred list — a view interface carries no annotation of
            // this processor's own, so it could never come back through getSymbolsWithAnnotation();
            // it is retried from `getUsedViews()` instead, on every later round.
            if (!viewDecl.validate()) {
                neverProcessed[qualifiedName] = deferral(viewDecl, "view interface")
                continue
            }

            processedViews += qualifiedName
            neverProcessed -= qualifiedName

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

    private fun deferral(decl: KSDeclaration, kind: String): Deferral {
        val location = decl.location as? FileLocation
        return Deferral(kind, location?.let { "${it.filePath}:${it.lineNumber}" } ?: "unknown location")
    }

    /** `@InjectPresenter` fields and the `@ProvidePresenter`/`@ProvidePresenterTag` methods bound to them. */
    private fun KSDeclaration.isMoxyMember(): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString()?.startsWith(MOXY_PRESENTER_PACKAGE) == true }

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
        for ((qualifiedName, deferral) in neverProcessed) {
            logger.error(
                "$qualifiedName (${deferral.kind}) at ${deferral.location} could not be processed: a type it " +
                    "declares is still unresolved after the final KSP round, so it is missing from MoxyReflector. " +
                    "That class would compile and then get no presenter at all at runtime, so the build is failed " +
                    "here instead. Check the types of its supertypes and of its moxy-annotated members " +
                    "(@InjectPresenter field, @ProvidePresenter/@ProvidePresenterTag return types; for a view " +
                    "interface, its method signatures) — a type generated by kapt (Dagger and the like) is never " +
                    "visible to KSP and cannot be used in those positions.",
            )
        }

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

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
    }
}

class MoxyKspProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        MoxyKspProcessor(environment.codeGenerator, environment.logger, environment.options)
}
