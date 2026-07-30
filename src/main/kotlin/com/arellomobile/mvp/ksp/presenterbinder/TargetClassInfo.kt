package com.arellomobile.mvp.ksp.presenterbinder

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

/** Port of `com.arellomobile.mvp.compiler.presenterbinder.TargetClassInfo`. */
class TargetClassInfo(val containerDecl: KSClassDeclaration, val fields: List<TargetPresenterField>) {
    val name: ClassName = containerDecl.toClassName()
}
