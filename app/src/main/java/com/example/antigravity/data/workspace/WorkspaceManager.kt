package com.example.antigravity.data.workspace

import java.io.File

class WorkspaceManager(
    private val baseFilesDir: File
) {
    val defaultWorkspaceDir: File
        get() = File(baseFilesDir, "workspace")

    private var customWorkspaceDir: File? = null

    init {
        ensureWorkspaceReady()
    }

    fun ensureWorkspaceReady(): Boolean {
        val active = getActiveWorkspace()
        return if (!active.exists()) {
            active.mkdirs()
        } else {
            active.isDirectory
        }
    }

    fun getActiveWorkspace(): File {
        return customWorkspaceDir ?: defaultWorkspaceDir.also {
            if (!it.exists()) it.mkdirs()
        }
    }

    fun getWorkspacePath(): String {
        return getActiveWorkspace().absolutePath
    }

    fun setCustomWorkspacePath(path: String?): Boolean {
        if (path.isNullOrBlank()) {
            customWorkspaceDir = null
            return true
        }
        val file = File(path.trim())
        if (file.exists() && file.isDirectory && file.canWrite()) {
            customWorkspaceDir = file
            return true
        }
        if (!file.exists() && file.mkdirs()) {
            customWorkspaceDir = file
            return true
        }
        return false
    }
}
