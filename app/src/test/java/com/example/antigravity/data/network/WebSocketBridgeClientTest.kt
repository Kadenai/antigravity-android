package com.example.antigravity.data.network

import com.example.antigravity.data.client.BridgeEvent
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.model.ClientConfigureMessage
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.PromptHistory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64

class WebSocketBridgeClientTest {
    @Test
    fun terminalStartup_configuresSessionBeforeSendingAndDoesNotLaunchAgainOnReuse() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val startedServer = Channel<ServerSocket>(1)
        var starts = 0
        val config = ClientConfigureMessage(workspace = "~/project α", automatic = true)
        val client = WebSocketBridgeClient("ws://127.0.0.1:$port", configuration = config, prepareTerminal = {
            starts++
            startedServer.send(ServerSocket(port))
        })
        val events = Channel<BridgeEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.incomingEvents.collect { events.send(it) } }
        val allowSession = Channel<Unit>(1)
        val serverJob = async(Dispatchers.IO) {
            val server = startedServer.receive()
            server.use {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val headers = input.bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }.toList()
                        val key = headers.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                        output.flush()
                        sendFrame(output, """{"type":"hello","protocol":4}""")
                        val requested = Json.parseToJsonElement(readFrame(input)).jsonObject
                        assertEquals("configure", requested["type"]?.jsonPrimitive?.content)
                        assertEquals("~/project α", requested["workspace"]?.jsonPrimitive?.content)
                        assertEquals("true", requested["automatic"]?.jsonPrimitive?.content)
                        allowSession.receive()
                        sendFrame(output, """{"type":"session","workspace":"/home/project","automatic":true,"conversation_id":"same-id"}""")
                        val prompt = Json.parseToJsonElement(readFrame(input)).jsonObject
                        assertEquals("prompt", prompt["type"]?.jsonPrimitive?.content)
                        sendFrame(output, """{"type":"status","state":"idle"}""")
                        sendFrame(output, """{"type":"turn_complete"}""")
                        while (input.read() != -1) { }
                    }
                }
            }
        }
        try {
            withTimeout(10000) {
                repeat(2) {
                    client.start()
                    assertFalse(client.sendPrompt("too early"))
                    allowSession.send(Unit)
                    client.status.first { it.connectionState == ConnectionState.CONNECTED }
                    assertEquals("same-id", client.status.value.conversationId)
                    assertTrue(client.sendPrompt("hello"))
                    while (events.receive() != BridgeEvent.TurnComplete) { }
                    client.disconnect()
                }
                assertEquals(1, starts)
                assertEquals(AGYState.IDLE, client.status.value.agyState)
            }
        } finally {
            client.stop()
            collector.cancelAndJoin()
            serverJob.cancelAndJoin()
        }
    }

    @Test
    fun realWebSocket_sendsMessageTypesAndPreservesOutputBeforeCompletion() = runBlocking {
        val server = ServerSocket(0)
        val client = WebSocketBridgeClient("ws://127.0.0.1:${server.localPort}")
        val events = Channel<BridgeEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.incomingEvents.collect { delay(1); events.send(it) }
        }
        val serverJob = async(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val headers = input.bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }.toList()
                val key = headers.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                val files = Json.parseToJsonElement(readFrame(input)).jsonObject
                assertEquals("files", files["type"]?.jsonPrimitive?.content)
                assertEquals("/home/projeto", files["root"]?.jsonPrimitive?.content)
                sendFrame(output, """{"type":"files","root":"/home/projeto","parent":"/home","paths":[],"files":["/home/projeto/ação.txt"]}""")
                val prompt = Json.parseToJsonElement(readFrame(input)).jsonObject
                assertEquals("prompt", prompt["type"]?.jsonPrimitive?.content)
                assertEquals("  código\n\tlinha", prompt["content"]?.jsonPrimitive?.content)
                val history = Json.decodeFromString<List<PromptHistory>>(prompt["history"].toString())
                assertEquals(listOf(PromptHistory("user", "antes")), history)
                val interrupt = Json.parseToJsonElement(readFrame(input)).jsonObject
                assertEquals("interrupt", interrupt["type"]?.jsonPrimitive?.content)
                repeat(100) { sendFrame(output, """{"type":"output","stream":"stdout","chunk":"$it,"}""") }
                sendFrame(output, """{"type":"status","state":"error","message":"AGY não instalado"}""")
                sendFrame(output, """{"type":"turn_complete"}""")
                while (input.read() != -1) { /* aguarda stop() */ }
            }
        }
        try {
            withTimeout(5000) {
                client.start()
                client.status.first { it.connectionState == ConnectionState.CONNECTED }
                assertTrue(client.listFiles("/home/projeto"))
                assertTrue(client.sendPrompt("  código\n\tlinha", listOf(PromptHistory("user", "antes"))))
                assertTrue(client.interrupt())
                val received = mutableListOf<BridgeEvent>()
                do { received += events.receive() } while (received.last() != BridgeEvent.TurnComplete)
                assertEquals((0..99).joinToString("") { "$it," }, received.filterIsInstance<BridgeEvent.Output>().joinToString("") { it.chunk })
                assertTrue(received.filterIsInstance<BridgeEvent.Error>().any { it.message == "AGY não instalado" })
                assertEquals(listOf("/home/projeto/ação.txt"), received.filterIsInstance<BridgeEvent.Files>().single().files)
            }
        } finally {
            client.stop()
            collector.cancelAndJoin()
            server.close()
        }
        serverJob.await()
    }

    @Test
    fun outdatedBridge_disconnectsBeforeConfigureAndDoesNotLaunchAnotherBridge() = runBlocking {
        val server = ServerSocket(0)
        var starts = 0
        val client = WebSocketBridgeClient("ws://127.0.0.1:${server.localPort}",
            configuration = ClientConfigureMessage(), prepareTerminal = { starts++ })
        val events = Channel<BridgeEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.incomingEvents.collect { events.send(it) } }
        val serverJob = async(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val headers = input.bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }.toList()
                val key = headers.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                sendFrame(output, """{"type":"hello","protocol":3}""")
                sendFrame(output, """{"type":"status","state":"idle","message":"Conectado à ponte do terminal"}""")
                assertEquals(-1, input.read()) // Nenhum configure/prompt enviado à ponte antiga.
            }
        }
        try {
            withTimeout(10000) {
                client.start()
                val error = events.receive() as BridgeEvent.Error
                assertTrue(error.message.contains("Ponte antiga"))
                serverJob.await()
                assertEquals(ConnectionState.ERROR, client.status.value.connectionState)
                assertEquals(AGYState.ERROR, client.status.value.agyState)
                assertFalse(client.sendPrompt("hello"))
                assertEquals(0, starts)
            }
        } finally {
            client.stop()
            server.close()
            collector.cancelAndJoin()
            serverJob.cancelAndJoin()
        }
    }

    private fun readFrame(input: InputStream): String {
        input.read()
        val header = input.read()
        var length = header and 127
        if (length == 126) length = (input.read() shl 8) or input.read()
        val mask = ByteArray(4) { input.read().toByte() }
        return ByteArray(length) { index -> (input.read() xor (mask[index % 4].toInt() and 255)).toByte() }.toString(Charsets.UTF_8)
    }

    private fun sendFrame(output: OutputStream, text: String) {
        val bytes = text.toByteArray()
        output.write(0x81)
        if (bytes.size <= 125) output.write(bytes.size) else {
            output.write(126)
            output.write(bytes.size shr 8)
            output.write(bytes.size and 255)
        }
        output.write(bytes)
        output.flush()
    }
}
