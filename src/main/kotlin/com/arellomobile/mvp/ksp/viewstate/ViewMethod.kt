package com.arellomobile.mvp.ksp.viewstate

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
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
    val parameters: List<Param>
    val typeVariables: List<TypeVariableName> = functionDecl.typeParameters.map { it.toTypeVariableName() }
    val argumentsString: String

    var uniqueSuffix: String = ""

    init {
        val resolvedParameterTypes = runCatching { functionDecl.asMemberOf(containingType).parameterTypes }
            .getOrNull()

        parameters = functionDecl.parameters.mapIndexed { index, param ->
            val paramName = param.name?.asString() ?: "arg$index"
            val resolvedType = resolvedParameterTypes?.getOrNull(index) ?: param.type.resolve()
            val typeName = resolvedType.toTypeName()
            val isVararg = param.isVararg
            val type = if (isVararg) {
                typeName.varargElement()
            } else {
                typeName.platformAsNullable(resolvedType, param)
            }
            Param(paramName, type, isVararg)
        }

        // A vararg param must be spread into both the interface call (mvpView.method(...)) and the
        // generated Command's own (also vararg, see ViewStateClassGenerator) constructor call.
        argumentsString = parameters.joinToString(", ") { if (it.isVararg) "*${it.name}" else it.name }
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

/**
 * `KSValueParameter.type` is documented ("the reference to the type of the parameter") without
 * specifying whether, for a vararg parameter, that's the array type or its element type — and KSP1
 * (descriptor-based) and KSP2 (FIR-based) disagree in practice. This isn't version-sniffing: since
 * `KModifier.VARARG` re-adds the array-ness on the generated parameter regardless of which shape we
 * started from, normalizing to the element type is correct for either engine, not a workaround for
 * one of them.
 */
/**
 * A Java-declared view method's parameters resolve to *platform* types, which KotlinPoet can only
 * render as non-null — and a non-null parameter on a public Kotlin function gets an
 * `Intrinsics.checkNotNullParameter` call, so a Java caller passing null blows up inside the
 * generated `$$State` instead of reaching the view. The apt original generated Java and had no such
 * check, so this is a port regression, not a Moxy behaviour change.
 *
 * Platform means "nullability unknown"; the safe reading for a parameter we only ever pass through
 * is nullable. Anything Kotlin-declared keeps its declared nullability, as does a Java parameter
 * whose not-null annotation KSP resolves to [Nullability.NOT_NULL].
 *
 * A nullability annotation on the parameter overrides KSP's verdict, because whether KSP reports
 * one as NOT_NULL/NULLABLE or falls back to PLATFORM varies with the annotation artifact and the
 * KSP/Kotlin versions in use, while the Kotlin compiler reading the same Java source always honours
 * it. Disagreeing in either direction is a hard compile error — the parameter in the generated
 * `$$State` "overrides nothing" — so the annotation wins whenever there is one.
 *
 * Only the top level is coerced: type arguments are erased at runtime and never null-checked, and
 * vararg parameters go through [varargElement] instead (its element type is likewise unchecked).
 */
private fun TypeName.platformAsNullable(resolved: KSType, param: KSValueParameter): TypeName =
    when (param.declaredNullability() ?: resolved.nullability) {
        Nullability.NOT_NULL -> copy(nullable = false)
        else -> copy(nullable = true) // NULLABLE, and PLATFORM read as "unknown, so assume nullable".
    }

private val NOT_NULL_ANNOTATION_NAMES = setOf("NotNull", "NonNull", "Nonnull")
private val NULLABLE_ANNOTATION_NAMES = setOf("Nullable", "CheckForNull")

/** `null` when the parameter carries no nullability annotation this can recognise by name. */
private fun KSValueParameter.declaredNullability(): Nullability? =
    (annotations + type.annotations)
        .mapNotNull {
            when (it.shortName.asString()) {
                in NULLABLE_ANNOTATION_NAMES -> Nullability.NULLABLE
                in NOT_NULL_ANNOTATION_NAMES -> Nullability.NOT_NULL
                else -> null
            }
        }
        .firstOrNull()

private fun TypeName.varargElement(): TypeName =
    ((this as? ParameterizedTypeName)?.takeIf { it.rawType == ARRAY }?.typeArguments?.single() ?: this)
        .let { if (it is WildcardTypeName) it.outTypes.single() else it }
