package com.arellomobile.mvp.ksp.viewstateprovider

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName

/** Port of `com.arellomobile.mvp.compiler.viewstateprovider.PresenterInfo`. */
class PresenterInfo(val presenterDecl: KSClassDeclaration, val viewStateClassName: ClassName?) {
    val name: ClassName = presenterDecl.toClassName()
    val containingFile: KSFile? = presenterDecl.containingFile
}
