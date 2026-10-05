package com.example.antigravity.data.network

object AnsiCleaner {
    // Regex for standard CSI (Control Sequence Introducer) sequences: ESC [ ... [a-zA-Z]
    private val CSI_REGEX = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")
    
    // Regex for OSC (Operating System Command) sequences: ESC ] ... (BEL | ESC \)
    private val OSC_REGEX = Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
    
    // Regex for other 2-byte escape sequences like ESC M, ESC =, ESC >
    private val ESC_SIMPLE_REGEX = Regex("\u001B[@-Z\\\\-_]")

    /**
     * Cleans ANSI escape sequences and normalizes terminal line endings.
     */
    fun clean(input: String): String {
        if (input.isEmpty()) return ""
        
        var text = input
        // Remove OSC escape sequences
        text = OSC_REGEX.replace(text, "")
        // Remove CSI escape sequences
        text = CSI_REGEX.replace(text, "")
        // Remove simple 2-byte escape codes
        text = ESC_SIMPLE_REGEX.replace(text, "")

        // Normalize carriage returns:
        // When CLI uses \r without \n (spinners, progress bars), take the latest segment on that line
        val lines = text.split("\n").map { line ->
            if (line.contains("\r")) {
                val parts = line.split("\r").filter { it.isNotBlank() }
                parts.lastOrNull() ?: ""
            } else {
                line
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Extracts tool or system status messages if present (e.g. "Running command: ...")
     */
    fun extractStatusHint(chunk: String): String? {
        val cleanChunk = clean(chunk).trim()
        val lower = cleanChunk.lowercase()
        return when {
            lower.contains("running command") -> cleanChunk.lines().firstOrNull { it.contains("running command", ignoreCase = true) }
            lower.contains("viewing file") -> cleanChunk.lines().firstOrNull { it.contains("viewing file", ignoreCase = true) }
            lower.contains("thinking") -> "Pensando..."
            lower.contains("tool:") -> cleanChunk.lines().firstOrNull { it.contains("tool:", ignoreCase = true) }
            else -> null
        }
    }
}
