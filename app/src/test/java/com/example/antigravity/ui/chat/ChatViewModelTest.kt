package com.example.antigravity.ui.chat

import com.example.antigravity.data.client.AgyClient
import com.example.antigravity.data.client.BridgeEvent
import com.example.antigravity.data.client.StreamType
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.model.PromptHistory
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
    fun initialIdle_doesNotDiscardPendingResponse() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()

        viewModel.onInputChange("Test prompt")
        viewModel.sendPrompt()
        advanceUntilIdle()

        fakeClient.emitEvent(BridgeEvent.Status(AGYState.IDLE, "Idle"))
        fakeClient.emitEvent(BridgeEvent.Output(StreamType.STDOUT, "Resposta"))
        advanceUntilIdle()

        val assistantMsg = viewModel.messages.value.last()
        assertTrue(assistantMsg.isStreaming)
        assertEquals("Resposta", assistantMsg.content)
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
        fakeClient.emitEvent(BridgeEvent.Output(StreamType.STDOUT, "Último trecho"))
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        advanceUntilIdle()
        val assistantMsg = viewModel.messages.value.last()
        assertFalse(assistantMsg.isStreaming)
        assertEquals("Último trecho", assistantMsg.content)
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
    fun clearingViewModel_stopsClientImmediately() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("chat", viewModel)
        advanceUntilIdle()
        store.clear()
        assertEquals(1, fakeClient.stopCallCount)
    }

    @Test
    fun startupError_isVisibleInChatAndLogs() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()
        fakeClient.emitEvent(BridgeEvent.Error("AGY não instalado"))
        advanceUntilIdle()
        assertTrue(viewModel.messages.value.last().content.contains("AGY não instalado"))
        assertTrue(viewModel.rawTerminalLogs.value.contains("AGY não instalado"))
    }

    @Test
    fun prompt_preservesIndentationAndLineBreaks() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()
        val prompt = "    código\n\tlinha\n"
        viewModel.onInputChange(prompt)
        viewModel.sendPrompt()
        advanceUntilIdle()
        assertEquals(prompt, fakeClient.lastPromptSent)
    }

    @Test
    fun confirmedWorkspaceChange_startsSeparateChatAndPersistsSelection() = runTest(testDispatcher) {
        val preferences = com.example.antigravity.data.settings.FakeSharedPreferences()
        val repository = com.example.antigravity.data.settings.SettingsRepository(preferences)
        val viewModel = ChatViewModel(fakeClient, repository)
        advanceUntilIdle()
        viewModel.onInputChange("old project")
        viewModel.sendPrompt()
        advanceUntilIdle()
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        fakeClient.emitEvent(BridgeEvent.Session("/storage/emulated/0/new", false, true, "new-id"))
        advanceUntilIdle()
        assertEquals(1, viewModel.messages.value.size)
        assertTrue(viewModel.messages.value.single().content.contains("/storage/emulated/0/new"))
        assertEquals("/storage/emulated/0/new", repository.getTerminalSettings().workspace)
        fakeClient.emitEvent(BridgeEvent.Session("/storage/emulated/0/new", false, false, "new-id"))
        advanceUntilIdle()
        assertEquals(1, viewModel.messages.value.size)
    }

    @Test
    fun edit_truncatesLaterTurnsAndSendsOnlyEarlierContextWithFileReferences() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()
        viewModel.onInputChange("primeiro")
        viewModel.sendPrompt()
        advanceUntilIdle()
        fakeClient.emitEvent(BridgeEvent.Output(StreamType.STDOUT, "primeira resposta"))
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        advanceUntilIdle()
        val path = "/storage/emulated/0/Meu projeto/ação.txt"
        fakeClient.emitEvent(BridgeEvent.Files("", "", emptyList(), emptyList(), path))
        fakeClient.emitEvent(BridgeEvent.Files("", "", emptyList(), emptyList(), path))
        advanceUntilIdle()
        assertEquals(listOf(path), viewModel.draftFiles.value)
        viewModel.onInputChange("segundo")
        viewModel.sendPrompt()
        advanceUntilIdle()
        val editedId = viewModel.messages.value[3].id
        fakeClient.emitEvent(BridgeEvent.Output(StreamType.STDOUT, "resposta descartada"))
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        advanceUntilIdle()
        viewModel.onInputChange("terceiro")
        viewModel.sendPrompt()
        advanceUntilIdle()
        viewModel.editMessage(editedId)
        assertEquals(null, viewModel.editingMessageId.value) // Bloqueado durante streaming.
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        advanceUntilIdle()
        viewModel.onInputChange("rascunho")
        viewModel.editMessage(editedId)
        assertEquals(listOf(path), viewModel.draftFiles.value)
        viewModel.cancelEdit()
        assertEquals("rascunho", viewModel.inputText.value)
        assertEquals(7, viewModel.messages.value.size)
        viewModel.editMessage(editedId)
        viewModel.onInputChange("    corrigido\n\t😀")
        viewModel.sendPrompt()
        advanceUntilIdle()
        assertEquals(5, viewModel.messages.value.size)
        assertEquals(editedId, viewModel.messages.value[3].id)
        assertEquals(listOf(PromptHistory("user", "primeiro"), PromptHistory("assistant", "primeira resposta")), fakeClient.lastHistorySent)
        assertTrue(fakeClient.lastPromptSent!!.startsWith("    corrigido\n\t😀\n"))
        assertTrue(fakeClient.lastPromptSent!!.endsWith("@$path"))
        assertTrue(viewModel.messages.value.last().isStreaming)
        assertTrue(viewModel.draftFiles.value.isEmpty())
    }

    @Test
    fun editingFirstMessage_sendsEmptyHistoryAndFailedDispatchKeepsEditRetryable() = runTest(testDispatcher) {
        val viewModel = ChatViewModel(fakeClient)
        advanceUntilIdle()
        viewModel.onInputChange("original")
        viewModel.sendPrompt()
        advanceUntilIdle()
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        advanceUntilIdle()
        val id = viewModel.messages.value[1].id
        viewModel.editMessage(id)
        viewModel.onInputChange("editado")
        fakeClient.promptResponseSuccess = false
        viewModel.sendPrompt()
        advanceUntilIdle()
        assertEquals(emptyList<PromptHistory>(), fakeClient.lastHistorySent)
        assertEquals(id, viewModel.editingMessageId.value)
        assertEquals("editado", viewModel.inputText.value)
        assertEquals("original", viewModel.messages.value[1].content)
        fakeClient.promptResponseSuccess = true
        viewModel.sendPrompt()
        advanceUntilIdle()
        assertEquals(3, viewModel.messages.value.size)
        assertEquals("editado", viewModel.messages.value[1].content)
        assertEquals(null, viewModel.editingMessageId.value)
        fakeClient.emitEvent(BridgeEvent.TurnComplete)
        advanceUntilIdle()
        fakeClient.emitEvent(BridgeEvent.Files("", "", emptyList(), emptyList(), "/home/arquivo.pdf"))
        advanceUntilIdle()
        viewModel.sendPrompt() // Referência sem texto também pode ser enviada.
        advanceUntilIdle()
        assertTrue(fakeClient.lastPromptSent!!.endsWith("@/home/arquivo.pdf"))
        assertEquals(null, fakeClient.lastHistorySent)
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
    var lastHistorySent: List<PromptHistory>? = null
    var promptResponseSuccess = true

    override suspend fun start() {
        startCallCount++
    }

    override suspend fun sendPrompt(prompt: String, history: List<PromptHistory>?): Boolean {
        lastPromptSent = prompt
        lastHistorySent = history
        return promptResponseSuccess
    }

    override suspend fun interrupt(): Boolean {
        interruptCallCount++
        return true
    }

    override fun stop() {
        stopCallCount++
    }

    suspend fun emitEvent(event: BridgeEvent) {
        _incomingEvents.emit(event)
    }

    fun updateStatus(newStatus: BridgeStatus) {
        _status.value = newStatus
    }
}
