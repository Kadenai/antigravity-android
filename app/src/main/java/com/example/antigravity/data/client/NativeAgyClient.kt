package com.example.antigravity.data.client

import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class DefaultProcessRunner : ProcessRunner {
    override fun start(
        command: List<String>,
        directory: File,
        environment: Map<String, String>
    ): AgyProcess {
        val pb = ProcessBuilder(command)
        pb.directory(directory)
        pb.environment().putAll(environment)
        val process = pb.start()
        return JavaAgyProcess(process)
    }
}

class JavaAgyProcess(private val process: Process) : AgyProcess {
    override val inputStream: InputStream get() = process.inputStream
    override val errorStream: InputStream get() = process.errorStream
    override val outputStream: OutputStream get() = process.outputStream
    override val isAlive: Boolean get() = process.isAlive
    override val pid: Long
        get() {
            return try {
                val pidMethod = process.javaClass.getMethod("pid")
                (pidMethod.invoke(process) as? Number)?.toLong() ?: -1L
            } catch (_: Throwable) {
                try {
                    val field = process.javaClass.getDeclaredField("pid")
                    field.isAccessible = true
                    (field.get(process) as? Number)?.toLong() ?: -1L
                } catch (_: Throwable) {
                    -1L
                }
            }
        }

    override fun sendSignal(signal: Int): Boolean {
        return try {
            val p = pid
            if (p > 0) {
                val osClass = Class.forName("android.system.Os")
                val killMethod = osClass.getMethod(
                    "kill",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                killMethod.invoke(null, p.toInt(), signal)
                true
            } else {
                process.destroy()
                true
            }
        } catch (e: Throwable) {
            process.destroy()
            true
        }
    }

    override fun destroy() {
        process.destroy()
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        try {
            process.waitFor()
        } catch (_: InterruptedException) {
            -1
        }
    }
}

class NativeAgyClient(
    val binaryFile: File,
    val workspaceManager: WorkspaceManager,
    private val processRunner: ProcessRunner = DefaultProcessRunner(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AgyClient {

    private val _status = MutableStateFlow(
        BridgeStatus(
            connectionState = ConnectionState.DISCONNECTED,
            agyState = AGYState.IDLE,
            statusMessage = "Pronto para iniciar motor nativo",
            serverUrl = "local://native"
        )
    )
    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    override val incomingEvents: SharedFlow<BridgeEvent> = _incomingEvents.asSharedFlow()

    private var currentProcess: AgyProcess? = null
    private var scope: CoroutineScope? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var monitorJob: Job? = null

    override suspend fun start() {
        if (!binaryFile.exists()) {
            _status.value = _status.value.copy(
                connectionState = ConnectionState.ERROR,
                statusMessage = "Binário nativo não encontrado em: ${binaryFile.absolutePath}"
            )
            _incomingEvents.emit(
                BridgeEvent.Error("Binário nativo não encontrado em ${binaryFile.absolutePath}")
            )
            return
        }

        stop()

        _status.value = _status.value.copy(
            connectionState = ConnectionState.CONNECTING,
            statusMessage = "Iniciando processo nativo do Antigravity..."
        )

        try {
            val workspace = workspaceManager.getActiveWorkspace()
            val env = mutableMapOf<String, String>()
            val parentFile = binaryFile.parentFile
            if (parentFile != null) {
                env["PATH"] = "/system/bin:/system/xbin:" + parentFile.absolutePath
            }
            env["LANG"] = "en_US.UTF-8"
            env["LC_ALL"] = "en_US.UTF-8"

            val process = withContext(ioDispatcher) {
                processRunner.start(
                    listOf(binaryFile.absolutePath, "--accept-edits"),
                    workspace,
                    env
                )
            }
            currentProcess = process

            val clientScope = CoroutineScope(ioDispatcher)
            scope = clientScope

            stdoutJob = clientScope.launch {
                readStream(process.inputStream, StreamType.STDOUT)
            }

            stderrJob = clientScope.launch {
                readStream(process.errorStream, StreamType.STDERR)
            }

            monitorJob = clientScope.launch {
                val exitCode = process.waitFor()
                if (scope?.isActive == true) {
                    _status.value = _status.value.copy(
                        connectionState = ConnectionState.DISCONNECTED,
                        agyState = AGYState.IDLE,
                        statusMessage = "Processo finalizado ($exitCode)"
                    )
                }
            }

            _status.value = _status.value.copy(
                connectionState = ConnectionState.CONNECTED,
                agyState = AGYState.IDLE,
                statusMessage = "Motor nativo em execução"
            )
            _incomingEvents.emit(BridgeEvent.Status(AGYState.IDLE, "Motor nativo pronto"))

        } catch (e: Throwable) {
            _status.value = _status.value.copy(
                connectionState = ConnectionState.ERROR,
                statusMessage = "Erro ao iniciar processo: ${e.message}"
            )
            _incomingEvents.emit(BridgeEvent.Error("Falha ao iniciar processo nativo: ${e.message}", e))
        }
    }

    private suspend fun readStream(stream: InputStream, streamType: StreamType) {
        val buffer = ByteArray(512)
        try {
            while (scope?.isActive == true) {
                val read = withContext(ioDispatcher) {
                    stream.read(buffer)
                }
                if (read == -1) break
                val chunk = String(buffer, 0, read, Charsets.UTF_8)
                _incomingEvents.emit(BridgeEvent.Output(streamType, chunk))
            }
        } catch (_: Throwable) {
            // Stream closed
        }
    }

    override suspend fun sendPrompt(prompt: String): Boolean {
        val process = currentProcess
        if (process == null || !process.isAlive) {
            start()
        }
        val targetProcess = currentProcess ?: return false
        return try {
            withContext(ioDispatcher) {
                targetProcess.outputStream.write((prompt + "\n").toByteArray(Charsets.UTF_8))
                targetProcess.outputStream.flush()
            }
            _status.value = _status.value.copy(
                agyState = AGYState.RUNNING,
                statusMessage = "Executando prompt..."
            )
            _incomingEvents.emit(BridgeEvent.Status(AGYState.RUNNING, "Executando prompt"))
            true
        } catch (e: Throwable) {
            _status.value = _status.value.copy(
                agyState = AGYState.IDLE,
                statusMessage = "Erro ao enviar prompt: ${e.message}"
            )
            false
        }
    }

    override suspend fun interrupt(): Boolean {
        val process = currentProcess
        if (process == null || !process.isAlive) {
            return false
        }
        val sent = process.sendSignal(2) // SIGINT = 2
        if (sent) {
            _status.value = _status.value.copy(
                agyState = AGYState.INTERRUPTED,
                statusMessage = "Execução interrompida"
            )
            _incomingEvents.emit(BridgeEvent.Status(AGYState.INTERRUPTED, "Execução interrompida"))
        }
        return sent
    }

    override suspend fun stop() {
        stdoutJob?.cancel()
        stderrJob?.cancel()
        monitorJob?.cancel()
        currentProcess?.destroy()
        currentProcess = null
        _status.value = _status.value.copy(
            connectionState = ConnectionState.DISCONNECTED,
            agyState = AGYState.IDLE,
            statusMessage = "Motor nativo parado"
        )
    }
}
