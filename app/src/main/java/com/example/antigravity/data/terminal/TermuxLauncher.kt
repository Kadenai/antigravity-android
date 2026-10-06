package com.example.antigravity.data.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class TermuxLauncher(private val context: Context) {
    companion object {
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        val failures = MutableSharedFlow<String>(extraBufferCapacity = 8)

        internal fun failureMessage(exit: Int, error: Int, diagnostic: String): String? {
            if (exit == 0 && error == -1) return null
            val guidance = when {
                exit == 137 -> "A ponte foi encerrada à força (SIGKILL). Toque para reconectar. Se ocorrer sem encerrar a sessão, confira as restrições de bateria do Termux."
                diagnostic.contains("allow-external-apps") -> "Verifique allow-external-apps=true em ~/.termux/termux.properties e execute termux-reload-settings."
                else -> "Confira a saída do Termux para identificar a causa."
            }
            return "Termux falhou (código $exit): $diagnostic\n$guidance"
        }
    }

    suspend fun launch() = withContext(Dispatchers.IO) {
        val intent = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
        check(context.packageManager.resolveService(intent, 0) != null) { "Instale e abra o Termux 0.109+ antes de configurar o início automático." }
        check(ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "Conceda a permissão Executar comandos no Termux nas configurações do Android."
        }
        val source = context.assets.open("agy_bridge.py").use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        val script = """
            set -e
            command -v python >/dev/null || { echo 'Instale Python no Termux: pkg install python' >&2; exit 1; }
            mkdir -p ~/.antigravity-android
            printf %s '$source' | base64 -d > ~/.antigravity-android/agy_bridge.py.tmp.${'$'}${'$'}
            mv ~/.antigravity-android/agy_bridge.py.tmp.${'$'}${'$'} ~/.antigravity-android/agy_bridge.py
            exec python -u ~/.antigravity-android/agy_bridge.py --reuse
        """.trimIndent()
        val callback = PendingIntent.getBroadcast(context, 0, Intent(context, TermuxResultReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "\$PREFIX/bin/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "~/")
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Antigravity Android")
        check(context.startService(intent) != null) { "Termux não aceitou a inicialização da ponte." }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = intent.getBundleExtra("result") ?: return
        val exit = result.getInt("exitCode", 0)
        val error = result.getInt("err", -1)
        if (exit != 0 || error != -1) {
            val diagnostic = listOf(result.getString("errmsg"), result.getString("stderr"), result.getString("stdout"))
                .filterNot { it.isNullOrBlank() }.joinToString("\n").takeLast(8000)
            TermuxLauncher.failureMessage(exit, error, diagnostic)?.let { TermuxLauncher.failures.tryEmit(it) }
        }
    }
}
