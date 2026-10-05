package com.example.antigravity.data.client

import java.io.File
import java.io.InputStream
import java.io.OutputStream

interface AgyProcess {
    val inputStream: InputStream
    val errorStream: InputStream
    val outputStream: OutputStream
    val isAlive: Boolean
    val pid: Long
    fun sendSignal(signal: Int): Boolean
    fun destroy()
    suspend fun waitFor(): Int
}

interface ProcessRunner {
    fun start(
        command: List<String>,
        directory: File,
        environment: Map<String, String>
    ): AgyProcess
}
