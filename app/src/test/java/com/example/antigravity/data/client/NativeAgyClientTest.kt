package com.example.antigravity.data.client

import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.workspace.WorkspaceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAgyClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var binaryDir: File
    private lateinit var binaryFile: File
    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setUp() {
        val appFiles = tempFolder.newFolder("files")
        binaryDir = tempFolder.newFolder("lib")
        binaryFile = File(binaryDir, "libantigravity.so").apply {
            writeText("#!/bin/sh\necho mock")
        }
        workspaceManager = WorkspaceManager(appFiles)
    }

    class FakeAgyProcess(
        override val inputStream: InputStream = ByteArrayInputStream("Hello from AGY\n> ".toByteArray()),
        override val errorStream: InputStream = ByteArrayInputStream(ByteArray(0)),
        override val outputStream: OutputStream = ByteArrayOutputStream(),
        override var isAlive: Boolean = true,
        override val pid: Long = 1234
    ) : AgyProcess {
        var signalSent: Int? = null
        var destroyed: Boolean = false
        val exitDeferred = kotlinx.coroutines.CompletableDeferred<Int>()

        override fun sendSignal(signal: Int): Boolean {
            signalSent = signal
            return true
        }

        override fun destroy() {
            destroyed = true
            isAlive = false
            exitDeferred.complete(0)
        }

        override suspend fun waitFor(): Int = exitDeferred.await()
    }

    class FakeProcessRunner(val fakeProcess: FakeAgyProcess) : ProcessRunner {
        var lastCommand: List<String>? = null
        var lastDir: File? = null
        var lastEnv: Map<String, String>? = null

        override fun start(
            command: List<String>,
            directory: File,
            environment: Map<String, String>
        ): AgyProcess {
            lastCommand = command
            lastDir = directory
            lastEnv = environment
            return fakeProcess
        }
    }

    @Test
    fun start_missingBinary_emitsErrorStatus() = runTest {
        val nonExistentBinary = File(binaryDir, "non_existent.so")
        val client = NativeAgyClient(
            binaryFile = nonExistentBinary,
            workspaceManager = workspaceManager,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        client.start()

        val status = client.status.value
        assertEquals(ConnectionState.ERROR, status.connectionState)
        assertTrue(status.statusMessage.contains("não encontrado"))
    }

    @Test
    fun start_success_connectsAndStreamsOutput() = runTest {
        val fakeProcess = FakeAgyProcess(
            inputStream = ByteArrayInputStream("Antigravity ready\n".toByteArray())
        )
        val runner = FakeProcessRunner(fakeProcess)
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val client = NativeAgyClient(
            binaryFile = binaryFile,
            workspaceManager = workspaceManager,
            processRunner = runner,
            ioDispatcher = testDispatcher
        )

        val events = mutableListOf<BridgeEvent>()
        val job = launch(testDispatcher) {
            client.incomingEvents.collect { events.add(it) }
        }

        client.start()
        advanceUntilIdle()

        assertEquals(ConnectionState.CONNECTED, client.status.value.connectionState)
        assertEquals(AGYState.IDLE, client.status.value.agyState)
        assertTrue(events.any { it is BridgeEvent.Output && it.chunk.contains("Antigravity ready") })

        job.cancel()
    }

    @Test
    fun sendPrompt_writesToOutputStream() = runTest {
        val stdinStream = ByteArrayOutputStream()
        val fakeProcess = FakeAgyProcess(outputStream = stdinStream)
        val runner = FakeProcessRunner(fakeProcess)
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val client = NativeAgyClient(
            binaryFile = binaryFile,
            workspaceManager = workspaceManager,
            processRunner = runner,
            ioDispatcher = testDispatcher
        )

        client.start()
        advanceUntilIdle()

        val sent = client.sendPrompt("Hello Antigravity")
        advanceUntilIdle()

        assertTrue(sent)
        assertEquals("Hello Antigravity\n", stdinStream.toString("UTF-8"))
        assertEquals(AGYState.RUNNING, client.status.value.agyState)
    }

    @Test
    fun interrupt_sendsSigintToProcess() = runTest {
        val fakeProcess = FakeAgyProcess()
        val runner = FakeProcessRunner(fakeProcess)
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val client = NativeAgyClient(
            binaryFile = binaryFile,
            workspaceManager = workspaceManager,
            processRunner = runner,
            ioDispatcher = testDispatcher
        )

        client.start()
        advanceUntilIdle()

        val interrupted = client.interrupt()
        advanceUntilIdle()

        assertTrue(interrupted)
        assertEquals(2, fakeProcess.signalSent) // SIGINT = 2
        assertEquals(AGYState.INTERRUPTED, client.status.value.agyState)
    }

    @Test
    fun stop_destroysProcessAndDisconnects() = runTest {
        val fakeProcess = FakeAgyProcess()
        val runner = FakeProcessRunner(fakeProcess)
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val client = NativeAgyClient(
            binaryFile = binaryFile,
            workspaceManager = workspaceManager,
            processRunner = runner,
            ioDispatcher = testDispatcher
        )

        client.start()
        advanceUntilIdle()

        client.stop()
        advanceUntilIdle()

        assertTrue(fakeProcess.destroyed)
        assertEquals(ConnectionState.DISCONNECTED, client.status.value.connectionState)
    }
}
