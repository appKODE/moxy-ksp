package com.arellomobile.mvp.ksp.viewstate

import com.arellomobile.mvp.ksp.KspUtil
import com.arellomobile.mvp.ksp.KspUtil.superInterfaces
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

private const val STATE_STRATEGY_TYPE = "com.arellomobile.mvp.viewstate.strategy.StateStrategyType"
private const val ADD_TO_END_STRATEGY = "com.arellomobile.mvp.viewstate.strategy.AddToEndStrategy"
private const val UNIT_QUALIFIED_NAME = "kotlin.Unit"

/**
 * Port of `com.arellomobile.mvp.compiler.viewstate.ViewInterfaceProcessor`.
 *
 * Directly-declared methods are always authoritative; methods discovered while walking ancestor
 * interfaces (any depth) are deduped against each other and against the root — a conflicting
 * strategy/tag between two independently-inherited interfaces is reported (not thrown, unlike the
 * apt original's `IllegalStateException` — `KSPLogger.error` fails the KSP round with the same
 * rejection semantics without crashing the whole task).
 */
class ViewInterfaceProcessor(private val resolver: Resolver, private val logger: KSPLogger) {
    private val usedStrategies = LinkedHashSet<KSClassDeclaration>()
    private lateinit var viewInterfaceDecl: KSClassDeclaration

    fun getUsedStrategies(): List<KSClassDeclaration> = usedStrategies.toList()

    fun process(element: KSClassDeclaration): ViewInterfaceInfo {
        viewInterfaceDecl = element
        val containingType = element.asStarProjectedType()
        val interfaceDefaultStrategy = interfaceStateStrategyType(element)

        val rootMethods = mutableListOf<ViewMethod>()
        getMethods(element, containingType, interfaceDefaultStrategy, emptyList(), rootMethods)

        val inheritedMethods = mutableListOf<ViewMethod>()
        iterateInterfaces(element, containingType, interfaceDefaultStrategy, rootMethods, inheritedMethods)

        val allMethods = rootMethods + inheritedMethods

        // Allow methods with the same name across the flattened list — give repeats a unique suffix.
        val counter = HashMap<String, Int>()
        for (method in allMethods) {
            val seen = counter[method.name]
            if (seen != null && seen > 0) {
                method.uniqueSuffix = seen.toString()
            }
            counter[method.name] = (seen ?: 0) + 1
        }

        return ViewInterfaceInfo(element, allMethods)
    }

    private fun getMethods(
        typeDecl: KSClassDeclaration,
        containingType: com.google.devtools.ksp.symbol.KSType,
        defaultStrategy: KSClassDeclaration?,
        rootMethods: List<ViewMethod>,
        superinterfacesMethods: MutableList<ViewMethod>,
    ) {
        for (functionDecl in KspUtil.run { typeDecl.directFunctions() }) {
            val returnDecl = functionDecl.returnType?.resolve()?.declaration
            if (returnDecl?.qualifiedName?.asString() != UNIT_QUALIFIED_NAME) {
                logger.error(
                    "You are trying generate ViewState for ${typeDecl.simpleName.asString()}. " +
                        "But it contains non-Unit method \"${functionDecl.simpleName.asString()}\" that returns " +
                        "${functionDecl.returnType?.resolve()}. " +
                        "See more here: https://github.com/Arello-Mobile/Moxy/issues/2",
                    functionDecl,
                )
            }

            val annotation = KspUtil.findAnnotation(functionDecl, STATE_STRATEGY_TYPE)
            val strategyFromAnnotation = KspUtil.annotationValueAsType(annotation, "value")?.declaration as? KSClassDeclaration
            val strategyDecl = strategyFromAnnotation ?: defaultStrategy ?: defaultStateStrategy()

            val methodTag = KspUtil.annotationValueAsString(annotation, "tag") ?: functionDecl.simpleName.asString()

            usedStrategies.add(strategyDecl)

            val method = ViewMethod(containingType, functionDecl, strategyDecl, methodTag)

            if (rootMethods.contains(method)) continue

            val existingIndex = superinterfacesMethods.indexOf(method)
            if (existingIndex >= 0) {
                checkStrategyAndTagEquals(method, superinterfacesMethods[existingIndex])
                continue
            }

            superinterfacesMethods.add(method)
        }
    }

    private fun iterateInterfaces(
        parentDecl: KSClassDeclaration,
        containingType: com.google.devtools.ksp.symbol.KSType,
        parentDefaultStrategy: KSClassDeclaration?,
        rootMethods: List<ViewMethod>,
        superinterfacesMethods: MutableList<ViewMethod>,
    ) {
        for (superInterfaceRef in parentDecl.superInterfaces()) {
            val ifaceDecl = superInterfaceRef.resolve().declaration as? KSClassDeclaration ?: continue
            val defaultStrategy = parentDefaultStrategy ?: interfaceStateStrategyType(ifaceDecl)

            getMethods(ifaceDecl, containingType, defaultStrategy, rootMethods, superinterfacesMethods)
            iterateInterfaces(ifaceDecl, containingType, defaultStrategy, rootMethods, superinterfacesMethods)
        }
    }

    private fun checkStrategyAndTagEquals(method: ViewMethod, existingMethod: ViewMethod) {
        val differentParts = mutableListOf<String>()
        if (existingMethod.strategy.qualifiedName?.asString() != method.strategy.qualifiedName?.asString()) {
            differentParts += "strategies"
        }
        if (existingMethod.tag != method.tag) {
            differentParts += "tags"
        }

        if (differentParts.isNotEmpty()) {
            val arguments = method.parameters.joinToString(", ") { (name, type) -> "$name: $type" }
            val parts = differentParts.joinToString(" and ")
            logger.error(
                "Both ${existingMethod.enclosedClassName()} and ${method.enclosedClassName()} has method " +
                    "${method.name}($arguments) with different $parts. Override this method in " +
                    "${viewInterfaceDecl.simpleName.asString()} or make $parts equals",
                method.functionDecl,
            )
        }
    }

    private fun interfaceStateStrategyType(decl: KSClassDeclaration): KSClassDeclaration? {
        val annotation = KspUtil.findAnnotation(decl, STATE_STRATEGY_TYPE) ?: return null
        return KspUtil.annotationValueAsType(annotation, "value")?.declaration as? KSClassDeclaration
    }

    private fun defaultStateStrategy(): KSClassDeclaration =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(ADD_TO_END_STRATEGY))
            ?: error("$ADD_TO_END_STRATEGY not found on the compilation classpath")
}
