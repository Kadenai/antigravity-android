package com.example.antigravity.data.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxLauncherTest {
    @Test
    fun failureGuidanceMatchesTheReportedCause() {
        assertNull(TermuxLauncher.failureMessage(0, -1, "Ponte existente reutilizada."))
        val killed = TermuxLauncher.failureMessage(137, 2, "Sending SIGKILL to process on user request")!!
        assertTrue(killed.contains("SIGKILL to process on user request"))
        assertTrue(killed.contains("Toque para reconectar"))
        assertFalse(killed.contains("allow-external-apps"))
        assertTrue(TermuxLauncher.failureMessage(0, 2, "allow-external-apps is disabled")!!.contains("termux-reload-settings"))
        val missing = TermuxLauncher.failureMessage(1, -1, "Instale Python")!!
        assertTrue(missing.contains("Instale Python"))
        assertFalse(missing.contains("allow-external-apps"))
    }
}
