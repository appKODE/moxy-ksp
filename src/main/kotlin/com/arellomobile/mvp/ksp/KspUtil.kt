package com.arellomobile.mvp.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument

private const val MVP_PRESENTER_QUALIFIED_NAME = "com.arellomobile.mvp.MvpPresenter"

/**
 * Port of `com.arellomobile.mvp.compiler.Util` for KSP. Most of the original's string-based
 * plumbing (round-tripping a [TypeMirror] to a String then back to a `TypeElement`) is
 * unnecessary here: KSP hands out live [KSType]/[KSClassDeclaration] references directly, so
 * callers should just keep those instead of re-deriving them from a name.
 */
object KspUtil {

    fun findAnnotation(declaration: com.google.devtools.ksp.symbol.KSAnnotated, qualifiedName: String): KSAnnotation? =
        declaration.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }

    fun annotationValue(annotation: KSAnnotation?, name: String): KSValueArgument? =
        annotation?.arguments?.firstOrNull { it.name?.asString() == name }

    fun annotationValueAsType(annotation: KSAnnotation?, name: String): KSType? =
        annotationValue(annotation, name)?.value as? KSType

    fun annotationValueAsString(annotation: KSAnnotation?, name: String): String? =
        annotationValue(annotation, name)?.value as? String

    /**
     * For a `String` annotation member whose own default is the empty-string sentinel (`tag`,
     * `presenterId` throughout the `presenter` annotations) — unlike `javax.annotation.processing`,
     * KSP materializes annotation defaults into [KSAnnotation.arguments] rather than omitting
     * unspecified members, so "not specified" and "explicitly passed empty" are indistinguishable
     * here. Both mean the same thing to every caller (fall back to the field/method name), so both
     * collapse to `null`.
     */
    fun annotationValueAsNonEmptyString(annotation: KSAnnotation?, name: String): String? =
        annotationValueAsString(annotation, name)?.takeIf { it.isNotEmpty() }

    /**
     * For an enum-typed annotation member (e.g. `PresenterType type() default LOCAL`). KSP's exact
     * runtime representation of enum-valued annotation arguments isn't pinned down here — this
     * defensively extracts the enum constant's simple name from whatever `toString()` form the
     * value takes (`"LOCAL"`, `"PresenterType.LOCAL"`, or a fully-qualified form), verified against
     * the real build in the test suite.
     */
    fun annotationValueAsEnumName(annotation: KSAnnotation?, name: String): String? =
        annotationValue(annotation, name)?.value?.toString()?.substringAfterLast('.')

    fun hasEmptyConstructor(declaration: KSClassDeclaration): Boolean {
        // No primary constructor declared at all -> Kotlin synthesizes an implicit no-arg one.
        val primary = declaration.primaryConstructor ?: return true
        return primary.parameters.isEmpty()
    }

    fun decapitalize(value: String): String =
        if (value.isEmpty()) value else value.replaceFirstChar { it.lowercaseChar() }

    /** Direct superclass (not an interface), or null — mirrors `TypeElement.getSuperclass()`. */
    fun KSClassDeclaration.superclassOrNull(): KSClassDeclaration? =
        superTypes
            .map { it.resolve().declaration }
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.classKind == ClassKind.CLASS }

    /** Direct super-interfaces — mirrors `TypeElement.getInterfaces()`. */
    fun KSClassDeclaration.superInterfaces(): List<KSTypeReference> =
        superTypes.filter {
            (it.resolve().declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE
        }.toList()

    /** Directly-enclosed declarations only — no inherited-member walk (matches the original's use of `getEnclosedElements()`). */
    fun KSClassDeclaration.directFunctions(): List<KSFunctionDeclaration> =
        declarations.filterIsInstance<KSFunctionDeclaration>().toList()

    fun KSClassDeclaration.directProperties(): List<com.google.devtools.ksp.symbol.KSPropertyDeclaration> =
        declarations.filterIsInstance<com.google.devtools.ksp.symbol.KSPropertyDeclaration>().toList()

    /**
     * Walks up from [startDecl] (typed with [startArgs], positionally matching
     * `startDecl.typeParameters`) through `superclass` links until it reaches `MvpPresenter<View>`,
     * substituting type-parameter references with concrete types along the way, and returns the
     * resolved View declaration. Mirrors `PresenterInjectorRules.getViewClassFromGeneric` /
     * `InjectViewStateProcessor.getViewClassFromGeneric`, generalized so both call sites share one
     * implementation.
     *
     * [boundFallback] (type-parameter name -> its `MvpView`-bound declaration) covers the original's
     * `getChildInstanceOfClassFromGeneric` case: a raw/unparameterized presenter field usage falls
     * back to the presenter class's own declared type-parameter bound.
     */
    fun resolveMvpPresenterView(
        startDecl: KSClassDeclaration,
        startArgs: List<KSType?>,
        boundFallback: Map<String, KSClassDeclaration> = emptyMap(),
    ): KSClassDeclaration? {
        var currentDecl = startDecl
        var currentArgs = startArgs

        while (true) {
            if (currentDecl.qualifiedName?.asString() == MVP_PRESENTER_QUALIFIED_NAME) {
                val viewArg = currentArgs.firstOrNull() ?: return null
                val viewDecl = viewArg.declaration
                return if (viewDecl is KSTypeParameter) {
                    boundFallback[viewDecl.name.asString()]
                } else {
                    viewDecl as? KSClassDeclaration
                }
            }

            val typeParams = currentDecl.typeParameters
            val substitution = HashMap<String, KSType?>()
            typeParams.forEachIndexed { i, tp -> substitution[tp.name.asString()] = currentArgs.getOrNull(i) }

            val superclassDecl = currentDecl.superclassOrNull() ?: return null
            val superTypeRef = currentDecl.superTypes.firstOrNull {
                (it.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString() ==
                    superclassDecl.qualifiedName?.asString()
            } ?: return null
            val superType = superTypeRef.resolve()

            currentArgs = superType.arguments.map { arg ->
                val resolved = arg.type?.resolve() ?: return@map null
                val decl = resolved.declaration
                if (decl is KSTypeParameter) substitution[decl.name.asString()] ?: resolved else resolved
            }
            currentDecl = superclassDecl
        }
    }

    /** The `MvpView`-bound type parameters of [decl] (e.g. `class P<V : MyView>` -> `"V" to MyView`). */
    fun mvpViewBoundTypeParameters(decl: KSClassDeclaration, isMvpView: (KSClassDeclaration) -> Boolean): Map<String, KSClassDeclaration> =
        decl.typeParameters.mapNotNull { tp ->
            val boundDecl = tp.bounds.firstOrNull()?.resolve()?.declaration as? KSClassDeclaration ?: return@mapNotNull null
            if (isMvpView(boundDecl)) tp.name.asString() to boundDecl else null
        }.toMap()

    /** The class itself + every superclass + every (transitive) interface — mirrors `PresenterInjectorRules.getViewsType`. */
    fun collectSupertypesAndInterfaces(decl: KSClassDeclaration): Set<KSClassDeclaration> {
        val result = LinkedHashSet<KSClassDeclaration>()
        fun visit(current: KSClassDeclaration) {
            if (!result.add(current)) return
            for (superInterfaceRef in current.superInterfaces()) {
                val ifaceDecl = superInterfaceRef.resolve().declaration as? KSClassDeclaration ?: continue
                visit(ifaceDecl)
            }
            current.superclassOrNull()?.let { visit(it) }
        }
        visit(decl)
        return result
    }
}
