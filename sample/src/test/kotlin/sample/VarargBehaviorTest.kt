package sample

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VarargBehaviorTest {

    @Test
    fun `vararg view method replays with all spread arguments intact`() {
        val presenter = VarargPresenter()
        presenter.callShowFormatted()

        val view = RecordingVarargView()
        presenter.attach(view)

        assertThat(view.calls).containsExactly(1 to listOf("a", "b", "c"))
    }
}
