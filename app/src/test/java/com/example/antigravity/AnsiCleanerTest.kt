package com.example.antigravity

import com.example.antigravity.data.network.AnsiCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiCleanerTest {

    @Test
    fun testStripColors() {
        // Red color, bold, and reset
        val input = "\u001B[31mError:\u001B[0m \u001B[1mFile not found\u001B[0m"
        val expected = "Error: File not found"
        assertEquals(expected, AnsiCleaner.clean(input))
    }

    @Test
    fun testStripCursorAndClearLine() {
        val input = "\u001B[2K\u001B[1GReady to accept input"
        val expected = "Ready to accept input"
        assertEquals(expected, AnsiCleaner.clean(input))
    }

    @Test
    fun testSpinnerCarriageReturn() {
        val input = "⠋ Thinking...\r⠙ Thinking...\rDone!"
        val expected = "Done!"
        assertEquals(expected, AnsiCleaner.clean(input))
    }

    @Test
    fun testExtractStatusHint() {
        val input = "\u001B[32mRunning command: git status\u001B[0m"
        val hint = AnsiCleaner.extractStatusHint(input)
        assertTrue(hint != null && hint.contains("Running command: git status"))
    }
}
