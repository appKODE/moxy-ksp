package com.arellomobile.mvp.ksp.viewstate

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/** Port of `com.arellomobile.mvp.compiler.viewstate.ViewInterfaceInfo`. */
class ViewInterfaceInfo(val classDecl: KSClassDeclaration, val methods: List<ViewMethod>) {
    val name: ClassName = classDecl.toClassName()
    val typeVariables: List<TypeVariableName> = classDecl.typeParameters.map { it.toTypeVariableName() }

    val nameWithTypeVariables: TypeName =
        if (typeVariables.isEmpty()) name else name.parameterizedBy(typeVariables)
}
