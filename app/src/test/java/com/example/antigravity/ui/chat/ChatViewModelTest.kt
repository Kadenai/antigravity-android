package com.example.antigravity.ui.chat

import com.example.antigravity.data.client.AgyClient
import com.example.antigravity.data.client.BridgeEvent
import com.example.antigravity.data.client.StreamType
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var fakeClient: FakeAgyClient

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeClient = FakeAgyClient()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_startsClient() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        assertEquals(1, fakeClient.startCallCount)
        assertEquals(1, viewModel.messages.value.size) // Welcome message
    }

    @Test
    fun sendPrompt_addsMessagesAndDispatchesToClient() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.onInputChange("Hello Antigravity")
        viewModel.sendPrompt()
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(3, messages.size) // Welcome + User + Assistant placeholder
        assertEquals("Hello Antigravity", messages[1].content)
        assertTrue(messages[1].isUser)

        val assistantMsg = messages[2]
        assertEquals("", assistantMsg.content)
        assertFalse(assistantMsg.isUser)
        assertTrue(assistantMsg.isStreaming)
        assertEquals("Hello Antigravity", fakeClient.lastPromptSent)
    }

    @Test
    fun incomingOutput_appendsCleanedTextToActiveAssistantMessage() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.onInputChange("Test prompt")
        viewModel.sendPrompt()
        advanceUntilIdle()

        fakeClient.emitEvent(BridgeEvent.Output(StreamType.STDOUT, "\u001B[32mHello\u001B[0m "))
        fakeClient.emitEvent(BridgeEvent.Output(StreamType.STDOUT, "world!"))
        advanceUntilIdle()

        val messages = viewModel.messages.value
        val assistantMsg = messages.last()
        assertEquals("Hello world!", assistantMsg.content)
        assertTrue(assistantMsg.isStreaming)
        assertTrue(viewModel.rawTerminalLogs.value.contains("\u001B[32mHello\u001B[0m world!"))
    }

    @Test
    fun turnComplete_finishesStreaming() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.onInputChange("Test prompt")
        viewModel.sendPrompt()
        advanceUntilIdle()

        fakeClient.emitEvent(BridgeEvent.Output(StreamType.STDOUT, "Result"))
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        advanceUntilIdle()

        val messages = viewModel.messages.value
        val assistantMsg = messages.last()
        assertEquals("Result", assistantMsg.content)
        assertFalse(assistantMsg.isStreaming)
    }

    @Test
    fun statusIdle_finishesStreaming() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.onInputChange("Test prompt")
        viewModel.sendPrompt()
        advanceUntilIdle()

        fakeClient.emitEvent(BridgeEvent.Status(AGYState.IDLE, "Idle"))
        advanceUntilIdle()

        val assistantMsg = viewModel.messages.value.last()
        assertFalse(assistantMsg.isStreaming)
    }

    @Test
    fun errorEvent_attachesErrorToStreamingMessage() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.onInputChange("Test prompt")
        viewModel.sendPrompt()
        advanceUntilIdle()

        fakeClient.emitEvent(BridgeEvent.Error("Process exited unexpectedly"))
        advanceUntilIdle()

        val assistantMsg = viewModel.messages.value.last()
        assertTrue(assistantMsg.content.contains("⚠️ Erro: Process exited unexpectedly"))
        assertFalse(assistantMsg.isStreaming)
    }

    @Test
    fun sendInterrupt_callsClientInterruptAndFinishesStreaming() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.onInputChange("Long running task")
        viewModel.sendPrompt()
        advanceUntilIdle()

        viewModel.sendInterrupt()
        advanceUntilIdle()

        assertEquals(1, fakeClient.interruptCallCount)
        val assistantMsg = viewModel.messages.value.last()
        assertFalse(assistantMsg.isStreaming)
    }

    @Test
    fun reconnect_callsClientStart() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.reconnect()
        advanceUntilIdle()

        assertEquals(2, fakeClient.startCallCount)
    }

    @Test
    fun switchClientMode_stopsPreviousClientAndStartsNewClient() = runTest(testDispatcher) {
        val client1 = FakeAgyClient()
        val client2 = FakeAgyClient()
        val factory = object : com.example.antigravity.data.client.AgyClientFactory {
            override fun createClient(mode: com.example.antigravity.data.settings.ClientMode): AgyClient {
                return if (mode == com.example.antigravity.data.settings.ClientMode.NATIVE) client1 else client2
            }
        }

        val viewModel = ChatViewModel(
            initialClient = client1,
            clientFactory = factory
        )
        advanceUntilIdle()

        assertEquals(1, client1.startCallCount)

        viewModel.switchClientMode(com.example.antigravity.data.settings.ClientMode.REMOTE)
        advanceUntilIdle()

        assertEquals(1, client1.stopCallCount)
        assertEquals(1, client2.startCallCount)
        assertEquals(com.example.antigravity.data.settings.ClientMode.REMOTE, viewModel.clientMode.value)
    }
}

private class FakeAgyClient : AgyClient {
    private val _status = MutableStateFlow(
        BridgeStatus(
            connectionState = ConnectionState.CONNECTED,
            agyState = AGYState.IDLE
        )
    )
    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    override val incomingEvents: SharedFlow<BridgeEvent> = _incomingEvents.asSharedFlow()

    var startCallCount = 0
    var stopCallCount = 0
    var interruptCallCount = 0
    var lastPromptSent: String? = null
    var promptResponseSuccess = true

    override suspend fun start() {
        startCallCount++
    }

    override suspend fun sendPrompt(prompt: String): Boolean {
        lastPromptSent = prompt
        return promptResponseSuccess
    }

    override suspend fun interrupt(): Boolean {
        interruptCallCount++
        return true
    }

    override suspend fun stop() {
        stopCallCount++
    }

    suspend fun emitEvent(event: BridgeEvent) {
        _incomingEvents.emit(event)
    }

    fun updateStatus(newStatus: BridgeStatus) {
        _status.value = newStatus
    }
}
