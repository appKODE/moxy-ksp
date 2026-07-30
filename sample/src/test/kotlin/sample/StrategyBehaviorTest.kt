package sample

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Real behavioral verification for all five `StateStrategy` implementations moxy ships, run
 * against actually-generated `$$State`/`$$ViewStateProvider`/`MoxyReflector` classes in an ordinary
 * Gradle module with KSP genuinely applied. The unit test suite in the root module's
 * `ViewStateTest` only checks strategy *wiring* (that the right `StateStrategy` class reaches the
 * generated command's `getStrategyType()`) — actually driving `attachView`/`detachView` needs
 * `MoxyReflector` to be visible from `moxy.jar`'s `ViewCommands`, which is impossible inside
 * kotlin-compile-testing's isolated child classloader (see ViewStateTest's kdoc) but works fine here
 * since this module has no such isolation.
 */
class StrategyBehaviorTest {

    @Test
    fun `AddToEndStrategy replays every queued command in order`() {
        val presenter = StrategyPresenter()
        presenter.callAddToEnd("first")
        presenter.callAddToEnd("second")

        val view = RecordingStrategyView()
        presenter.attach(view)

        assertThat(view.addToEndCalls).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `AddToEndSingleStrategy collapses repeated calls to the same method into the latest one`() {
        val presenter = StrategyPresenter()
        presenter.callAddToEndSingle("stale")
        presenter.callAddToEndSingle("fresh")

        val view = RecordingStrategyView()
        presenter.attach(view)

        assertThat(view.addToEndSingleCalls).containsExactly("fresh")
    }

    @Test
    fun `SingleStateStrategy clears the entire queue, not just same-method entries`() {
        val presenter = StrategyPresenter()
        presenter.callAddToEnd("unrelated, queued first")
        presenter.callSingleState("wins")

        val view = RecordingStrategyView()
        presenter.attach(view)

        // SingleStateStrategy.beforeApply() clears the *whole* currentState queue before adding
        // itself — the earlier, differently-strategied AddToEnd command must be wiped out too.
        assertThat(view.addToEndCalls).isEmpty()
        assertThat(view.singleStateCalls).containsExactly("wins")
    }

    @Test
    fun `SkipStrategy never queues, so nothing replays on attach`() {
        val presenter = StrategyPresenter()
        presenter.callSkip("never delivered")

        val view = RecordingStrategyView()
        presenter.attach(view)

        assertThat(view.skipCalls).isEmpty()
    }

    @Test
    fun `OneExecutionStateStrategy replays once then does not replay again on a later attach`() {
        val presenter = StrategyPresenter()
        presenter.callOneExecution("only once")

        val firstView = RecordingStrategyView()
        presenter.attach(firstView)
        assertThat(firstView.oneExecutionCalls).containsExactly("only once")

        // afterApply() removed it from the queue during that first reapply — a second view
        // attaching later must not see it again.
        presenter.detach(firstView)
        val secondView = RecordingStrategyView()
        presenter.attach(secondView)
        assertThat(secondView.oneExecutionCalls).isEmpty()
    }
}
