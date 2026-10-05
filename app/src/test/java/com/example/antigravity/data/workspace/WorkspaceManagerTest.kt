package com.example.antigravity.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseFilesDir: File
    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setUp() {
        baseFilesDir = tempFolder.newFolder("files")
        workspaceManager = WorkspaceManager(baseFilesDir)
    }

    @Test
    fun defaultWorkspace_createsAndReturnsWorkspaceDirectory() {
        val workspace = workspaceManager.getActiveWorkspace()
        assertEquals(File(baseFilesDir, "workspace").absolutePath, workspace.absolutePath)
        assertTrue(workspace.exists())
        assertTrue(workspace.isDirectory)
    }

    @Test
    fun customWorkspace_validDirectory_updatesActiveWorkspace() {
        val customDir = tempFolder.newFolder("custom_project")
        val success = workspaceManager.setCustomWorkspacePath(customDir.absolutePath)
        assertTrue(success)
        assertEquals(customDir.absolutePath, workspaceManager.getActiveWorkspace().absolutePath)
    }

    @Test
    fun customWorkspace_invalidPath_returnsFalseAndPreservesDefault() {
        val invalidFile = tempFolder.newFile("regular_file.txt")
        val success = workspaceManager.setCustomWorkspacePath(invalidFile.absolutePath)
        assertFalse(success)
        assertEquals(File(baseFilesDir, "workspace").absolutePath, workspaceManager.getActiveWorkspace().absolutePath)
    }

    @Test
    fun customWorkspace_blankOrNull_resetsToDefault() {
        val customDir = tempFolder.newFolder("custom_project")
        workspaceManager.setCustomWorkspacePath(customDir.absolutePath)
        workspaceManager.setCustomWorkspacePath("")
        assertEquals(File(baseFilesDir, "workspace").absolutePath, workspaceManager.getActiveWorkspace().absolutePath)
    }
}
