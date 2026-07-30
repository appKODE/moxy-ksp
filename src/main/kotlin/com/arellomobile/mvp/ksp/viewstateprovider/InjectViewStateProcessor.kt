package com.arellomobile.mvp.ksp.viewstateprovider

import com.arellomobile.mvp.MvpProcessor
import com.arellomobile.mvp.ksp.KspUtil
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

private const val INJECT_VIEW_STATE = "com.arellomobile.mvp.InjectViewState"
private const val DEFAULT_VIEW_STATE = "com.arellomobile.mvp.DefaultViewState"
private const val DEFAULT_VIEW = "com.arellomobile.mvp.DefaultView"

/**
 * Port of `com.arellomobile.mvp.compiler.viewstateprovider.InjectViewStateProcessor`.
 *
 * KSP simplification: annotation `Class<...>` arguments resolve directly to a [KSType] via
 * [com.google.devtools.ksp.symbol.KSAnnotation.arguments] — there is no `MirroredTypeException`
 * workaround needed (that's an artifact of `javax.annotation.processing` reflection semantics that
 * doesn't exist in KSP).
 */
class InjectViewStateProcessor {
    private val usedViews = LinkedHashSet<KSClassDeclaration>()
    private val presenterDecls = mutableListOf<KSClassDeclaration>()

    fun getUsedViews(): Set<KSClassDeclaration> = usedViews
    fun getPresenterClassNames(): List<KSClassDeclaration> = presenterDecls

    fun process(presenterDecl: KSClassDeclaration): PresenterInfo {
        presenterDecls.add(presenterDecl)
        return PresenterInfo(presenterDecl, resolveViewStateClassName(presenterDecl))
    }

    private fun resolveViewStateClassName(presenterDecl: KSClassDeclaration): ClassName? {
        viewStateClassFromAnnotationParams(presenterDecl)?.let { return it }

        val viewDecl = viewClassFromAnnotationParams(presenterDecl)
            ?: viewClassFromGeneric(presenterDecl)
            ?: return null

        usedViews.add(viewDecl)
        return viewStateClassNameFor(viewDecl)
    }

    /** `@InjectViewState(SomeCustomViewState::class)` — referenced directly, no codegen triggered. */
    private fun viewStateClassFromAnnotationParams(presenterDecl: KSClassDeclaration): ClassName? {
        val annotation = KspUtil.findAnnotation(presenterDecl, INJECT_VIEW_STATE) ?: return null
        val value = KspUtil.annotationValueAsType(annotation, "value") ?: return null
        val decl = value.declaration as? KSClassDeclaration ?: return null
        if (decl.qualifiedName?.asString() == DEFAULT_VIEW_STATE) return null
        return decl.toClassName()
    }

    /** `@InjectViewState(view = SomeView::class)` — triggers `$$State` codegen for `SomeView`. */
    private fun viewClassFromAnnotationParams(presenterDecl: KSClassDeclaration): KSClassDeclaration? {
        val annotation = KspUtil.findAnnotation(presenterDecl, INJECT_VIEW_STATE) ?: return null
        val value = KspUtil.annotationValueAsType(annotation, "view") ?: return null
        val decl = value.declaration as? KSClassDeclaration ?: return null
        if (decl.qualifiedName?.asString() == DEFAULT_VIEW) return null
        return decl
    }

    /** No `@InjectViewState` params supplied — infer the View from `MvpPresenter<View>`'s own generic argument. */
    private fun viewClassFromGeneric(presenterDecl: KSClassDeclaration): KSClassDeclaration? =
        KspUtil.resolveMvpPresenterView(
            startDecl = presenterDecl,
            startArgs = presenterDecl.typeParameters.map { null as KSType? },
        )

    private fun viewStateClassNameFor(viewDecl: KSClassDeclaration): ClassName {
        val viewClassName = viewDecl.toClassName()
        return ClassName(viewClassName.packageName, viewClassName.simpleName + MvpProcessor.VIEW_STATE_SUFFIX)
    }
}
