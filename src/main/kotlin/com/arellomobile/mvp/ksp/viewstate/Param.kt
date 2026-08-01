package com.arellomobile.mvp.ksp.viewstate

import com.squareup.kotlinpoet.TypeName

/**
 * A view-interface method parameter.
 *
 * [isVararg] comes straight from `KSValueParameter.isVararg` — unambiguous and engine-independent,
 * unlike inferring vararg-ness from the shape of the resolved parameter type (KSP1 and KSP2 disagree
 * on whether that shape is the array or its element; see [ViewMethod]'s `varargElement()`).
 */
data class Param(val name: String, val type: TypeName, val isVararg: Boolean)
