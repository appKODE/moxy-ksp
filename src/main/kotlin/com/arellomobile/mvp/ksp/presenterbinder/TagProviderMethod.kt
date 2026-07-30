package com.arellomobile.mvp.ksp.presenterbinder

import com.arellomobile.mvp.presenter.PresenterType
import com.google.devtools.ksp.symbol.KSType

/** Port of `com.arellomobile.mvp.compiler.presenterbinder.TagProviderMethod`. */
class TagProviderMethod(
    val presenterClass: KSType,
    val methodName: String,
    presenterTypeName: String?,
    val presenterId: String?,
) {
    val presenterType: PresenterType = presenterTypeName?.let(PresenterType::valueOf) ?: PresenterType.LOCAL
}
