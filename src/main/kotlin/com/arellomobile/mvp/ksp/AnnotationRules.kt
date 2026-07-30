package com.arellomobile.mvp.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier

/**
 * Port of `AnnotationRule`/`PresenterInjectorRules`: validates `@InjectPresenter` fields.
 *
 * KSP simplification vs. the apt original: `field.type.resolve()` already returns the fully
 * use-site-resolved [com.google.devtools.ksp.symbol.KSType] (concrete type arguments substituted),
 * so there is no need for the original's string round-trip through
 * `Util.getFullClassName`/`Util.fillGenerics` — everything here works directly with live
 * [KSClassDeclaration] references. The actual generic-resolution walk is shared with
 * `InjectViewStateProcessor` via [KspUtil.resolveMvpPresenterView].
 */
object InjectPresenterValidation {
    private const val MVP_VIEW = "com.arellomobile.mvp.MvpView"

    fun validate(field: KSPropertyDeclaration, logger: KSPLogger) {
        validateModifiersAndVisibility(field, logger)
        validateIsViewField(field, logger)
    }

    private fun validateModifiersAndVisibility(field: KSPropertyDeclaration, logger: KSPLogger) {
        if (Modifier.PRIVATE in field.modifiers || Modifier.PROTECTED in field.modifiers) {
            logger.error(
                "Field ${field.simpleName.asString()} of " +
                    "${(field.parentDeclaration as? KSClassDeclaration)?.simpleName?.asString()} can't be " +
                    "private/protected. Use public (the default) or internal.",
                field,
            )
        }

        var enclosing = field.parentDeclaration as? KSClassDeclaration
        while (enclosing != null && enclosing.classKind == ClassKind.CLASS) {
            if (Modifier.PRIVATE in enclosing.modifiers || Modifier.PROTECTED in enclosing.modifiers) {
                logger.error("${enclosing.simpleName.asString()} should be public", field)
                break
            }
            enclosing = enclosing.parentDeclaration as? KSClassDeclaration
        }
    }

    private fun validateIsViewField(field: KSPropertyDeclaration, logger: KSPLogger) {
        val fieldType = field.type.resolve()
        val presenterDecl = fieldType.declaration as? KSClassDeclaration ?: return

        val boundFallback = KspUtil.mvpViewBoundTypeParameters(presenterDecl, ::isMvpView)
        val viewDecl = KspUtil.resolveMvpPresenterView(
            startDecl = presenterDecl,
            startArgs = fieldType.arguments.map { it.type?.resolve() },
            boundFallback = boundFallback,
        ) ?: return

        val enclosing = field.parentDeclaration as? KSClassDeclaration ?: return
        val implementedTypes = KspUtil.collectSupertypesAndInterfaces(enclosing)

        if (implementedTypes.none { it.qualifiedName?.asString() == viewDecl.qualifiedName?.asString() }) {
            logger.error(
                "You can not use @InjectPresenter in classes that are not View, which is typified target Presenter",
                field,
            )
        }
    }

    private fun isMvpView(decl: KSClassDeclaration): Boolean {
        if (decl.qualifiedName?.asString() == MVP_VIEW) return true
        return KspUtil.collectSupertypesAndInterfaces(decl).any { it.qualifiedName?.asString() == MVP_VIEW }
    }
}
