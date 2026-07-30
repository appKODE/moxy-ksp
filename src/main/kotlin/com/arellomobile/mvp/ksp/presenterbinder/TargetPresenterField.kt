package com.arellomobile.mvp.ksp.presenterbinder

import com.arellomobile.mvp.MvpProcessor
import com.arellomobile.mvp.presenter.PresenterType
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

/**
 * Port of `com.arellomobile.mvp.compiler.presenterbinder.TargetPresenterField`.
 *
 * The original apt/JavaPoet code stripped a parameterized field type (e.g. `MyPresenter<Foo>`) down
 * to its raw `ClassName` for *both* the `Class<?>` literal in the binder's constructor call and the
 * cast in `bind()`, since `presenter as (raw) MyPresenter` assigned to a `MyPresenter<Foo>`-typed
 * field is only legal in Java's unchecked-conversion leniency. Kotlin has no raw types at all — a
 * star-projected `MyPresenter<*>` is neither assignable to a concretely-typed `MyPresenter<Foo>`
 * property nor a valid constructor-call type argument. Since KSP already resolves the field's full
 * concrete type, using it directly ([typeNameForUsage]) is both simpler and strictly more
 * type-safe than reproducing the original's raw-type erasure.
 */
class TargetPresenterField(
    val fieldType: KSType,
    val name: String,
    presenterTypeName: String?,
    val tag: String?,
    val presenterId: String?,
) {
    val declaration: KSClassDeclaration = fieldType.declaration as KSClassDeclaration
    val isParametrized: Boolean = fieldType.arguments.isNotEmpty()

    /** Raw class reference — correct for `::class.java`, which never needs type arguments. */
    val typeName: ClassName = declaration.toClassName()

    /** Full concrete type — correct for a cast or a constructor call. */
    val typeNameForUsage: TypeName = fieldType.toTypeName()

    val presenterType: PresenterType = presenterTypeName?.let(PresenterType::valueOf) ?: PresenterType.LOCAL

    val generatedClassName: String get() = name + MvpProcessor.PRESENTER_BINDER_INNER_SUFFIX

    var presenterProviderMethodName: String? = null
    var presenterTagProviderMethodName: String? = null
}
