package com.arellomobile.mvp.ksp.presenterbinder

import com.arellomobile.mvp.presenter.PresenterType
import com.google.devtools.ksp.symbol.KSType

/** Port of `com.arellomobile.mvp.compiler.presenterbinder.PresenterProviderMethod`. */
class PresenterProviderMethod(
    val returnType: KSType,
    val name: String,
    presenterTypeName: String?,
    val tag: String?,
    val presenterId: String?,
) {
    val presenterType: PresenterType = presenterTypeName?.let(PresenterType::valueOf) ?: PresenterType.LOCAL
}
