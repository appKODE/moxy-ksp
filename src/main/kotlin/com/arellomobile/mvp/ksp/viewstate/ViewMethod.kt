package com.arellomobile.mvp.ksp.viewstate

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeVariableName

/**
 * Port of `com.arellomobile.mvp.compiler.viewstate.ViewMethod`.
 *
 * `KSFunctionDeclaration.asMemberOf` is the direct KSP equivalent of the apt original's
 * `Types.asMemberOf(targetInterfaceType, methodElement)` call (added in 1.5.6 for generic view
 * support) — it resolves [functionDecl]'s parameter types as seen from [containingType], which
 * correctly substitutes inherited-interface generics (e.g. `ExtendsOfGenericView`). [containingType]
 * is the view interface's own star-projected self type: for the overwhelmingly common non-generic
 * view interface this loses nothing (there are no type arguments to project away); for a genuinely
 * generic view interface, method parameters may resolve to the type parameter's upper bound rather
 * than the symbolic type variable — verified against the `Generic*View` fixtures in the test suite.
 */
class ViewMethod(
    containingType: KSType,
    val functionDecl: KSFunctionDeclaration,
    val strategy: KSClassDeclaration,
    val tag: String,
) {
    val name: String = functionDecl.simpleName.asString()
    val parameters: List<Pair<String, TypeName>>
    val typeVariables: List<TypeVariableName> = functionDecl.typeParameters.map { it.toTypeVariableName() }
    val argumentsString: String

    var uniqueSuffix: String = ""

    init {
        val resolvedParameterTypes = runCatching { functionDecl.asMemberOf(containingType).parameterTypes }
            .getOrNull()

        parameters = functionDecl.parameters.mapIndexed { index, param ->
            val paramName = param.name?.asString() ?: "arg$index"
            val resolvedType = resolvedParameterTypes?.getOrNull(index) ?: param.type.resolve()
            paramName to resolvedType.toTypeName()
        }

        argumentsString = parameters.joinToString(", ") { it.first }
    }

    fun commandClassName(): String =
        name.replaceFirstChar { it.uppercaseChar() } + uniqueSuffix + "Command"

    fun enclosedClassName(): String =
        (functionDecl.parentDeclaration as? KSClassDeclaration)?.qualifiedName?.asString() ?: ""

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewMethod) return false
        return name == other.name && parameters == other.parameters
    }

    override fun hashCode(): Int = 31 * name.hashCode() + parameters.hashCode()
}
