package sample

import com.arellomobile.mvp.InjectViewState
import com.arellomobile.mvp.MvpPresenter
import com.arellomobile.mvp.MvpView
import com.arellomobile.mvp.viewstate.MvpViewState
import com.arellomobile.mvp.viewstate.strategy.AddToEndSingleStrategy
import com.arellomobile.mvp.viewstate.strategy.OneExecutionStateStrategy
import com.arellomobile.mvp.viewstate.strategy.SingleStateStrategy
import com.arellomobile.mvp.viewstate.strategy.SkipStrategy
import com.arellomobile.mvp.viewstate.strategy.StateStrategyType

/** One method per strategy, covering all five `StateStrategy` implementations moxy ships. */
interface StrategyView : MvpView {
    fun addToEnd(value: String)

    @StateStrategyType(AddToEndSingleStrategy::class)
    fun addToEndSingle(value: String)

    @StateStrategyType(SingleStateStrategy::class)
    fun singleState(value: String)

    @StateStrategyType(SkipStrategy::class)
    fun skip(value: String)

    @StateStrategyType(OneExecutionStateStrategy::class)
    fun oneExecution(value: String)
}

@InjectViewState
class StrategyPresenter : MvpPresenter<StrategyView>() {
    fun callAddToEnd(value: String) = viewState.addToEnd(value)
    fun callAddToEndSingle(value: String) = viewState.addToEndSingle(value)
    fun callSingleState(value: String) = viewState.singleState(value)
    fun callSkip(value: String) = viewState.skip(value)
    fun callOneExecution(value: String) = viewState.oneExecution(value)

    @Suppress("UNCHECKED_CAST")
    fun attach(view: StrategyView) = (viewState as MvpViewState<StrategyView>).attachView(view)

    @Suppress("UNCHECKED_CAST")
    fun detach(view: StrategyView) = (viewState as MvpViewState<StrategyView>).detachView(view)
}

class RecordingStrategyView : StrategyView {
    val addToEndCalls = mutableListOf<String>()
    val addToEndSingleCalls = mutableListOf<String>()
    val singleStateCalls = mutableListOf<String>()
    val skipCalls = mutableListOf<String>()
    val oneExecutionCalls = mutableListOf<String>()

    override fun addToEnd(value: String) { addToEndCalls += value }
    override fun addToEndSingle(value: String) { addToEndSingleCalls += value }
    override fun singleState(value: String) { singleStateCalls += value }
    override fun skip(value: String) { skipCalls += value }
    override fun oneExecution(value: String) { oneExecutionCalls += value }
}
