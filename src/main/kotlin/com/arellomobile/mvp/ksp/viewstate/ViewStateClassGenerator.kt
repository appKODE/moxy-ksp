package com.arellomobile.mvp.ksp.viewstate

import com.arellomobile.mvp.MvpProcessor
import com.arellomobile.mvp.ksp.KspUtil
import com.arellomobile.mvp.viewstate.MvpViewState
import com.arellomobile.mvp.viewstate.ViewCommand
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlin.random.Random

/** Port of `com.arellomobile.mvp.compiler.viewstate.ViewStateClassGenerator`. */
object ViewStateClassGenerator {

    fun generate(info: ViewInterfaceInfo): FileSpec {
        val viewName = info.name
        val nameWithTypeVariables = info.nameWithTypeVariables
        val generatedSimpleName = viewName.simpleName + MvpProcessor.VIEW_STATE_SUFFIX

        val classBuilder = TypeSpec.classBuilder(generatedSimpleName)
            .addTypeVariables(info.typeVariables)
            .superclass(MvpViewState::class.asClassName().parameterizedBy(nameWithTypeVariables))
            .addSuperinterface(nameWithTypeVariables)

        for (method in info.methods) {
            val commandClass = generateCommandClass(method, nameWithTypeVariables)
            classBuilder.addType(commandClass)
            classBuilder.addFunction(generateOverrideFunction(method, nameWithTypeVariables, commandClass))
        }

        return FileSpec.builder(viewName.packageName, generatedSimpleName)
            .addType(classBuilder.build())
            .build()
    }

    private fun generateCommandClass(method: ViewMethod, viewTypeName: TypeName): TypeSpec {
        val applyFun = FunSpec.builder("apply")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("mvpView", viewTypeName)
            .addStatement("mvpView.%L(%L)", method.name, method.argumentsString)
            .build()

        val classBuilder = TypeSpec.classBuilder(method.commandClassName())
            .addTypeVariables(method.typeVariables)
            .superclass(ViewCommand::class.asClassName().parameterizedBy(viewTypeName))
            // TODO: private/internal — kept public to match the original's own "TODO: private" note.
            .addSuperclassConstructorParameter("%S, %T::class.java", method.tag, method.strategy.toClassName())
            .addFunction(applyFun)

        val constructorBuilder = FunSpec.constructorBuilder()
        for ((paramName, paramType) in method.parameters) {
            constructorBuilder.addParameter(paramName, paramType)
            classBuilder.addProperty(PropertySpec.builder(paramName, paramType).initializer(paramName).build())
        }
        classBuilder.primaryConstructor(constructorBuilder.build())

        return classBuilder.build()
    }

    private fun generateOverrideFunction(method: ViewMethod, viewTypeName: TypeName, commandClass: TypeSpec): FunSpec {
        var commandFieldName = KspUtil.decapitalize(method.commandClassName())
        while (method.argumentsString.contains(commandFieldName)) {
            commandFieldName += Random.nextInt(10)
        }

        val builder = FunSpec.builder(method.name)
            .addModifiers(KModifier.OVERRIDE)

        method.typeVariables.forEach { builder.addTypeVariable(it) }
        method.parameters.forEach { (paramName, paramType) -> builder.addParameter(paramName, paramType) }

        builder
            .addStatement("val %L = %N(%L)", commandFieldName, commandClass, method.argumentsString)
            .addStatement("mViewCommands.beforeApply(%L)", commandFieldName)
            .addCode("\n")
            .beginControlFlow("if (mViews == null || mViews.isEmpty())")
            .addStatement("return")
            .endControlFlow()
            .addCode("\n")
            .beginControlFlow("for (view in mViews)")
            .addStatement("view.%L(%L)", method.name, method.argumentsString)
            .endControlFlow()
            .addCode("\n")
            .addStatement("mViewCommands.afterApply(%L)", commandFieldName)

        return builder.build()
    }
}
