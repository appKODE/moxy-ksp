package sample

import com.arellomobile.mvp.InjectViewState
import com.arellomobile.mvp.MvpPresenter
import com.arellomobile.mvp.MvpView
import com.arellomobile.mvp.viewstate.MvpViewState

/**
 * A vararg view method — proves the generated `$$State` override is a real Kotlin `vararg`, callable
 * with natural vararg syntax, not a plain `Array` parameter that forces callers to build an array by
 * hand. This is exactly the shape the root module's `ViewStateVarargTest` covers via reflection; here
 * it's exercised as actual call-site source, compiled in an ordinary Gradle module with KSP genuinely
 * applied, the same way a real consumer app would use it.
 */
interface VarargView : MvpView {
    fun showFormatted(label: Int, vararg formatArgs: String)
}

@InjectViewState
class VarargPresenter : MvpPresenter<VarargView>() {
    fun callShowFormatted() {
        // Natural vararg call syntax: individual arguments, no array construction.
        viewState.showFormatted(1, "a", "b", "c")
    }

    @Suppress("UNCHECKED_CAST")
    fun attach(view: VarargView) = (viewState as MvpViewState<VarargView>).attachView(view)
}

class RecordingVarargView : VarargView {
    val calls = mutableListOf<Pair<Int, List<String>>>()

    override fun showFormatted(label: Int, vararg formatArgs: String) {
        calls += label to formatArgs.toList()
    }
}
