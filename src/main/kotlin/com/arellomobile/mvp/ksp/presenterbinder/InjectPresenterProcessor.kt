package com.arellomobile.mvp.ksp.presenterbinder

import com.arellomobile.mvp.ksp.KspUtil
import com.arellomobile.mvp.ksp.KspUtil.directFunctions
import com.arellomobile.mvp.ksp.KspUtil.directProperties
import com.arellomobile.mvp.ksp.KspUtil.superclassOrNull
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

private const val INJECT_PRESENTER = "com.arellomobile.mvp.presenter.InjectPresenter"
private const val PROVIDE_PRESENTER = "com.arellomobile.mvp.presenter.ProvidePresenter"
private const val PROVIDE_PRESENTER_TAG = "com.arellomobile.mvp.presenter.ProvidePresenterTag"

/** Port of `com.arellomobile.mvp.compiler.presenterbinder.InjectPresenterProcessor`. */
class InjectPresenterProcessor {
    // Stored as ClassName (a plain value), not KSClassDeclaration: MoxyKspProcessor.finish() reads
    // these after every processing round has completed, when the KSP2 Analysis-API session that
    // backed the declaration's lazy accessors (.qualifiedName, .toClassName(), equals/hashCode) is
    // already invalid, throwing KaInvalidLifetimeOwnerAccessException. Resolved here, once, while
    // the round that discovered `container` is still fresh.
    private val presentersContainers = mutableListOf<ClassName>()
    private val containerSuperclassChains = HashMap<ClassName, List<ClassName>>()

    fun getPresentersContainers(): List<ClassName> = presentersContainers.toList()

    fun getContainerSuperclassChains(): Map<ClassName, List<ClassName>> = containerSuperclassChains

    // No need to dedup against `presentersContainers` here: the caller (MoxyKspProcessor) already
    // dedups per-round by qualified-name string before ever calling process() for a given container.
    fun process(field: KSPropertyDeclaration): TargetClassInfo? {
        val container = field.parentDeclaration as? KSClassDeclaration
            ?: error("Only class properties can be annotated @InjectPresenter: $field")

        presentersContainers.add(container.toClassName())
        containerSuperclassChains[container.toClassName()] = superclassChain(container)

        val fields = collectFields(container)
        bindProvidersToFields(fields, collectPresenterProviders(container))
        bindTagProvidersToFields(fields, collectTagProviders(container))

        return TargetClassInfo(container, fields)
    }

    private fun superclassChain(container: KSClassDeclaration): List<ClassName> {
        val chain = mutableListOf<ClassName>()
        var current = container.superclassOrNull()
        while (current != null) {
            chain.add(current.toClassName())
            current = current.superclassOrNull()
        }
        return chain
    }

    private fun collectFields(container: KSClassDeclaration): List<TargetPresenterField> =
        container.directProperties().mapNotNull { property ->
            val annotation = KspUtil.findAnnotation(property, INJECT_PRESENTER) ?: return@mapNotNull null
            TargetPresenterField(
                fieldType = property.type.resolve(),
                name = property.simpleName.asString(),
                presenterTypeName = KspUtil.annotationValueAsEnumName(annotation, "type"),
                tag = KspUtil.annotationValueAsNonEmptyString(annotation, "tag"),
                presenterId = KspUtil.annotationValueAsNonEmptyString(annotation, "presenterId"),
            )
        }

    private fun collectPresenterProviders(container: KSClassDeclaration): List<PresenterProviderMethod> =
        container.directFunctions().mapNotNull { function ->
            val annotation = KspUtil.findAnnotation(function, PROVIDE_PRESENTER) ?: return@mapNotNull null
            PresenterProviderMethod(
                returnType = function.returnType?.resolve() ?: return@mapNotNull null,
                name = function.simpleName.asString(),
                presenterTypeName = KspUtil.annotationValueAsEnumName(annotation, "type"),
                tag = KspUtil.annotationValueAsNonEmptyString(annotation, "tag"),
                presenterId = KspUtil.annotationValueAsNonEmptyString(annotation, "presenterId"),
            )
        }

    private fun collectTagProviders(container: KSClassDeclaration): List<TagProviderMethod> =
        container.directFunctions().mapNotNull { function ->
            val annotation = KspUtil.findAnnotation(function, PROVIDE_PRESENTER_TAG) ?: return@mapNotNull null
            TagProviderMethod(
                presenterClass = KspUtil.annotationValueAsType(annotation, "presenterClass") ?: return@mapNotNull null,
                methodName = function.simpleName.asString(),
                presenterTypeName = KspUtil.annotationValueAsEnumName(annotation, "type"),
                presenterId = KspUtil.annotationValueAsNonEmptyString(annotation, "presenterId"),
            )
        }

    private fun bindProvidersToFields(fields: List<TargetPresenterField>, providers: List<PresenterProviderMethod>) {
        if (fields.isEmpty() || providers.isEmpty()) return

        for (provider in providers) {
            for (field in fields) {
                if (field.declaration.qualifiedName?.asString() != provider.returnType.declaration.qualifiedName?.asString()) continue
                if (field.presenterType != provider.presenterType) continue
                if (field.tag == null && provider.tag != null) continue
                if (field.tag != null && field.tag != provider.tag) continue
                if (field.presenterId == null && provider.presenterId != null) continue
                if (field.presenterId != null && field.presenterId != provider.presenterId) continue

                field.presenterProviderMethodName = provider.name
            }
        }
    }

    private fun bindTagProvidersToFields(fields: List<TargetPresenterField>, providers: List<TagProviderMethod>) {
        if (fields.isEmpty() || providers.isEmpty()) return

        for (provider in providers) {
            for (field in fields) {
                if (field.declaration.qualifiedName?.asString() != provider.presenterClass.declaration.qualifiedName?.asString()) continue
                if (field.presenterType != provider.presenterType) continue
                if (field.presenterId == null && provider.presenterId != null) continue
                if (field.presenterId != null && field.presenterId != provider.presenterId) continue

                field.presenterTagProviderMethodName = provider.methodName
            }
        }
    }
}
