package ai.ankimcp.ankidroid.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectTest {
    private val loaded = MutableStateFlow(false)
    private val foreground = MutableStateFlow(true)
    private var connects = 0

    @Test
    fun `connects once credentials are loaded while in the foreground`() = runTest {
        val job = launch { autoConnectWhenForeground(loaded, foreground, { true }) { connects++ } }
        runCurrent()
        assertEquals(0, connects)

        loaded.value = true
        runCurrent()

        assertEquals(1, connects)
        assertEquals(true, job.isCompleted)
    }

    @Test
    fun `waits for the foreground when credentials finish loading in the background`() = runTest {
        launch { autoConnectWhenForeground(loaded, foreground, { true }) { connects++ } }
        foreground.value = false
        loaded.value = true
        runCurrent()
        assertEquals(0, connects)

        foreground.value = true
        runCurrent()

        assertEquals(1, connects)
    }

    @Test
    fun `conditions are checked when it is time to connect, not before`() = runTest {
        var keepConnected = true
        foreground.value = false
        loaded.value = true
        launch { autoConnectWhenForeground(loaded, foreground, { keepConnected }) { connects++ } }
        runCurrent()

        keepConnected = false
        foreground.value = true
        runCurrent()

        assertEquals(0, connects)
    }
}
