package com.arellomobile.mvp.ksp.presenterbinder

import com.arellomobile.mvp.MvpPresenter
import com.arellomobile.mvp.MvpProcessor
import com.arellomobile.mvp.MvpView
import com.arellomobile.mvp.PresenterBinder
import com.arellomobile.mvp.ksp.KspUtil
import com.arellomobile.mvp.presenter.PresenterField
import com.arellomobile.mvp.presenter.PresenterType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/** Port of `com.arellomobile.mvp.compiler.presenterbinder.PresenterBinderClassGenerator`. */
object PresenterBinderClassGenerator {

    fun generate(targetClassInfo: TargetClassInfo): FileSpec {
        val targetClassName = targetClassInfo.name
        val fields = targetClassInfo.fields
        val containerSimpleName = targetClassName.simpleNames.joinToString("$")
        val generatedSimpleName = containerSimpleName + MvpProcessor.PRESENTER_BINDER_SUFFIX

        val classBuilder = TypeSpec.classBuilder(generatedSimpleName)
            .superclass(PresenterBinder::class.asClassName().parameterizedBy(targetClassName))

        for (field in fields) {
            classBuilder.addType(generatePresenterBinderClass(field, targetClassName))
        }
        classBuilder.addFunction(generateGetPresenterFieldsFunction(fields, targetClassName))

        return FileSpec.builder(targetClassName.packageName, generatedSimpleName)
            .addType(classBuilder.build())
            .build()
    }

    private fun generateGetPresenterFieldsFunction(fields: List<TargetPresenterField>, containerClassName: ClassName): FunSpec {
        val presenterFieldOfContainer = PresenterField::class.asClassName().parameterizedBy(containerClassName)

        val builder = FunSpec.builder("getPresenterFields")
            .addModifiers(KModifier.OVERRIDE)
            .returns(List::class.asClassName().parameterizedBy(presenterFieldOfContainer))

        builder.addStatement(
            "val presenters = %T<%T>(%L)",
            ArrayList::class.asClassName(),
            presenterFieldOfContainer,
            fields.size,
        )
        for (field in fields) {
            builder.addStatement("presenters.add(%L())", field.generatedClassName)
        }
        builder.addStatement("return presenters")

        return builder.build()
    }

    private fun generatePresenterBinderClass(field: TargetPresenterField, targetClassName: ClassName): TypeSpec {
        val tag = field.tag ?: field.name

        // %S can't take a nullable String? arg (kotlinpoet's format-arg vararg is non-null Any) —
        // build the presenterId argument explicitly so an absent id renders the `null` keyword.
        val presenterIdArg = if (field.presenterId != null) CodeBlock.of("%S", field.presenterId) else CodeBlock.of("null")
        val superclassCtorArgs = CodeBlock.builder()
            .add("%S, %T.%L, ", tag, PresenterType::class.asClassName(), field.presenterType.name)
            .add(presenterIdArg)
            .add(", %T::class.java", field.typeName)
            .build()

        val classBuilder = TypeSpec.classBuilder(field.generatedClassName)
            .superclass(PresenterField::class.asClassName().parameterizedBy(targetClassName))
            .addSuperclassConstructorParameter(superclassCtorArgs)
            .addFunction(generateBindFunction(field, targetClassName))
            .addFunction(generateProvidePresenterFunction(field, targetClassName))

        field.presenterTagProviderMethodName?.let { tagProviderMethodName ->
            classBuilder.addFunction(generateGetTagFunction(tagProviderMethodName, targetClassName))
        }

        return classBuilder.build()
    }

    private fun generateBindFunction(field: TargetPresenterField, targetClassName: ClassName): FunSpec =
        FunSpec.builder("bind")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("target", targetClassName)
            .addParameter("presenter", MvpPresenter::class.asClassName().parameterizedBy(STAR))
            .addStatement("target.%L = presenter as %T", field.name, field.typeNameForUsage)
            .build()

    private fun generateProvidePresenterFunction(field: TargetPresenterField, targetClassName: ClassName): FunSpec {
        val returnType = MvpPresenter::class.asClassName()
            .parameterizedBy(WildcardTypeName.producerOf(MvpView::class.asClassName()))

        val builder = FunSpec.builder("providePresenter")
            .addModifiers(KModifier.OVERRIDE)
            .returns(returnType)
            .addParameter("delegated", targetClassName)

        val providerMethodName = field.presenterProviderMethodName
        if (providerMethodName != null) {
            builder.addStatement("return delegated.%L()", providerMethodName)
        } else if (KspUtil.hasEmptyConstructor(field.declaration)) {
            builder.addStatement("return %T()", field.typeNameForUsage)
        } else {
            builder.addStatement(
                "throw %T(%S)",
                IllegalStateException::class,
                "${field.declaration} has not default constructor. You can apply @ProvidePresenter to " +
                    "some method which will construct Presenter. Also you can make it default constructor",
            )
        }

        return builder.build()
    }

    private fun generateGetTagFunction(tagProviderMethodName: String, targetClassName: ClassName): FunSpec =
        FunSpec.builder("getTag")
            .addModifiers(KModifier.OVERRIDE)
            .returns(String::class)
            .addParameter("delegated", targetClassName)
            .addStatement("return delegated.%L().toString()", tagProviderMethodName)
            .build()
}
