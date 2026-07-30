package com.arellomobile.mvp.ksp.viewstateprovider

import com.arellomobile.mvp.MvpProcessor
import com.arellomobile.mvp.MvpView
import com.arellomobile.mvp.ViewStateProvider
import com.arellomobile.mvp.viewstate.MvpViewState
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/** Port of `com.arellomobile.mvp.compiler.viewstateprovider.ViewStateProviderClassGenerator`. */
object ViewStateProviderClassGenerator {

    fun generate(presenterInfo: PresenterInfo): FileSpec {
        val generatedSimpleName = presenterInfo.name.simpleName + MvpProcessor.VIEW_STATE_PROVIDER_SUFFIX

        val typeSpec = TypeSpec.classBuilder(generatedSimpleName)
            .superclass(ViewStateProvider::class.asClassName())
            .addFunction(generateGetViewStateFunction(presenterInfo))
            .build()

        return FileSpec.builder(presenterInfo.name.packageName, generatedSimpleName)
            .addType(typeSpec)
            .build()
    }

    private fun generateGetViewStateFunction(presenterInfo: PresenterInfo): FunSpec {
        val returnType = MvpViewState::class.asClassName()
            .parameterizedBy(WildcardTypeName.producerOf(MvpView::class.asClassName()))

        val builder = FunSpec.builder("getViewState")
            .addModifiers(KModifier.OVERRIDE)
            .returns(returnType)

        val viewStateClassName = presenterInfo.viewStateClassName
        if (viewStateClassName == null) {
            builder.addStatement(
                "throw %T(%S)",
                RuntimeException::class,
                "${presenterInfo.name.canonicalName} should has view",
            )
        } else {
            builder.addStatement("return %T()", viewStateClassName)
        }

        return builder.build()
    }
}
