# Native Standalone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full native standalone support in Antigravity Android, allowing the Antigravity CLI binary to execute directly inside the app sandbox via `ProcessBuilder` without requiring Termux or Python bridge.

**Architecture:** A unified `AgyClient` architecture where `NativeAgyClient` spawns the CLI binary from `nativeLibraryDir` with IO coroutines piping stdout/stderr into `BridgeEvent` flows. A `WorkspaceManager` maintains the project working directory in app internal storage, while `SettingsRepository` enables switching between Native Standalone mode and Remote WebSocket mode.

**Tech Stack:** Kotlin 2.3, Jetpack Compose / Material 3, Kotlin Coroutines & Flow, Android ProcessBuilder / POSIX signals, JUnit 4.

**Spec:** [docs/superpowers/specs/2026-10-05-native-standalone-architecture-design.md](file:///c:/Users/Levi/Documents/Projects/Antigravity%20Android/docs/superpowers/specs/2026-10-05-native-standalone-architecture-design.md)

## Global Constraints

- Android API: Compile SDK 36, Min SDK 24, Target SDK 36.
- Native Binary: `libantigravity.so` located in `app/src/main/jniLibs/arm64-v8a/`.
- App Sandbox Execution: Working directory default `context.filesDir/workspace`, HOME directory `context.filesDir`.
- Zero External Network Traffic in Native Mode: Native execution must not bind or connect to loopback sockets.
- Backward Compatibility: Remote WebSocket mode (`WebSocketBridgeClient`) must remain intact and switchable via Settings.
- Testability: All core classes must be testable via standard JVM unit tests (`testDebugUnitTest`) without crashing on Android OS stubs.

## Review Focus

1. **Process crash or unexpected exit:** When the native CLI exits or crashes, `NativeAgyClient` transitions to `AGYState.IDLE` with an error message and restarts cleanly on the next prompt.
2. **Missing native binary on unsupported ABI / device:** When `libantigravity.so` is absent in `nativeLibraryDir`, `NativeAgyClient` emits `BridgeStatus.ERROR` with a descriptive message rather than throwing an unhandled exception.
3. **SIGINT interruption when process is alive vs already stopped:** Invoking `interrupt()` when process is running dispatches the signal; invoking when stopped returns false without errors.
4. **ANSI terminal escape sequences in native stdout:** Native stdout chunks pass through `AnsiCleaner` so the Compose chat bubbles render clean markdown while raw logs retain the full terminal output.
5. **Switching engine modes dynamically:** Switching between Native and Remote modes properly terminates the previous client and starts the new one without leaking coroutines or process handles.

---

### Task 1: Native Packaging & Build Configuration

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/jniLibs/arm64-v8a/.gitkeep`

**Interfaces:**
- Consumes: Existing Android build configuration
- Produces: Native library extraction setting `extractNativeLibs="true"` and `ndk.abiFilters` configuration

- [ ] **Step 1: Check build script compatibility**

Verify that `app/build.gradle.kts` can declare `ndk.abiFilters` without syntax errors.

- [ ] **Step 2: Update AndroidManifest.xml and build.gradle.kts**

Add `android:extractNativeLibs="true"` to `<application>` in `app/src/main/AndroidManifest.xml`.
In `app/build.gradle.kts`, inside `android.defaultConfig`, add ABI filters configuration and create `app/src/main/jniLibs/arm64-v8a/.gitkeep`.

- [ ] **Step 3: Run build validation**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit build configuration**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/jniLibs/arm64-v8a/.gitkeep
git commit -m "build: configure native library extraction and arm64 jniLibs directory"
```

---

### Task 2: Workspace Management (`WorkspaceManager`)

**Files:**
- Create: `app/src/main/java/com/example/antigravity/data/workspace/WorkspaceManager.kt`
- Create: `app/src/test/java/com/example/antigravity/data/workspace/WorkspaceManagerTest.kt`

**Interfaces:**
- Consumes: `java.io.File`
- Produces: `WorkspaceManager(baseDir: File)`:
  - `fun getActiveWorkspace(): File`
  - `fun setCustomWorkspacePath(path: String?): Boolean`
  - `fun getWorkspacePath(): String`
  - `fun ensureWorkspaceReady(): Boolean`

- [ ] **Step 1: Write the failing unit tests for WorkspaceManager**

Create `app/src/test/java/com/example/antigravity/data/workspace/WorkspaceManagerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.data.workspace.WorkspaceManagerTest`
Expected: FAIL with compilation error: `Unresolved reference: workspace`

- [ ] **Step 3: Implement WorkspaceManager**

Create `app/src/main/java/com/example/antigravity/data/workspace/WorkspaceManager.kt`:

```kotlin
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
        if (path.isNull aerialOrBlank()) {
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

    private fun String?.isNull aerialOrBlank(): Boolean = this == null || this.trim().isEmpty()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.data.workspace.WorkspaceManagerTest`
Expected: PASS

- [ ] **Step 5: Commit WorkspaceManager**

```bash
git add app/src/main/java/com/example/antigravity/data/workspace/WorkspaceManager.kt app/src/test/java/com/example/antigravity/data/workspace/WorkspaceManagerTest.kt
git commit -m "feat(workspace): implement WorkspaceManager with default and custom directory support"
```

---

### Task 3: Process Abstraction & Native Client Engine (`NativeAgyClient`)

**Files:**
- Create: `app/src/main/java/com/example/antigravity/data/client/ProcessRunner.kt`
- Create: `app/src/main/java/com/example/antigravity/data/client/NativeAgyClient.kt`
- Create: `app/src/test/java/com/example/antigravity/data/client/NativeAgyClientTest.kt`

**Interfaces:**
- Consumes: `AgyClient`, `BridgeEvent`, `BridgeStatus`, `WorkspaceManager`
- Produces: `ProcessRunner`, `AgyProcess`, `NativeAgyClient : AgyClient`:
  - `start()`
  - `sendPrompt(prompt: String): Boolean`
  - `interrupt(): Boolean`
  - `stop()`

- [ ] **Step 1: Create ProcessRunner and AgyProcess interfaces**

Create `app/src/main/java/com/example/antigravity/data/client/ProcessRunner.kt`:

```kotlin
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
    fun waitFor(): Int
}

interface ProcessRunner {
    fun start(
        command: List<String>,
        directory: File,
        environment: Map<String, String>
    ): AgyProcess
}
```

- [ ] **Step 2: Write the failing unit tests for NativeAgyClient**

Create `app/src/test/java/com/example/antigravity/data/client/NativeAgyClientTest.kt`:

```kotlin
package com.example.antigravity.data.client

import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var workspaceDir: File
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

        override fun sendSignal(signal: Int): Boolean {
            signalSent = signal
            return true
        }

        override fun destroy() {
            destroyed = true
            isAlive = false
        }

        override fun waitFor(): Int = 0
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.data.client.NativeAgyClientTest`
Expected: FAIL with compilation error: `Unresolved reference: NativeAgyClient`

- [ ] **Step 4: Implement NativeAgyClient and DefaultProcessRunner**

Create `app/src/main/java/com/example/antigravity/data/client/NativeAgyClient.kt`:

Implement `DefaultProcessRunner` using `ProcessBuilder` with SIGINT support.
Implement `NativeAgyClient` adhering to `AgyClient` with:
- `_status: MutableStateFlow<BridgeStatus>`
- `_incomingEvents: MutableSharedFlow<BridgeEvent>`
- Background stdout / stderr readers on `ioDispatcher`
- Writing prompts to stdin + newline
- Sending SIGINT (signal 2) on interrupt
- Proper cleanup on `stop()`

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.data.client.NativeAgyClientTest`
Expected: PASS

- [ ] **Step 6: Commit NativeAgyClient**

```bash
git add app/src/main/java/com/example/antigravity/data/client/ProcessRunner.kt app/src/main/java/com/example/antigravity/data/client/NativeAgyClient.kt app/src/test/java/com/example/antigravity/data/client/NativeAgyClientTest.kt
git commit -m "feat(client): implement NativeAgyClient and ProcessRunner for standalone execution"
```

---

### Task 4: Engine Settings & Configuration Storage (`SettingsRepository`)

**Files:**
- Create: `app/src/main/java/com/example/antigravity/data/settings/SettingsRepository.kt`
- Create: `app/src/test/java/com/example/antigravity/data/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `ClientMode`, `android.content.SharedPreferences`
- Produces: `SettingsRepository`:
  - `fun getMode(): ClientMode`
  - `fun setMode(mode: ClientMode)`
  - `fun getServerUrl(): String`
  - `fun setServerUrl(url: String)`
  - `fun getWorkspacePath(): String?`
  - `fun setWorkspacePath(path: String?)`

- [ ] **Step 1: Write failing unit test for SettingsRepository**

Create `app/src/test/java/com/example/antigravity/data/settings/SettingsRepositoryTest.kt` with a fake in-memory `SharedPreferences` implementation.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.data.settings.SettingsRepositoryTest`
Expected: FAIL

- [ ] **Step 3: Implement ClientMode and SettingsRepository**

Create `app/src/main/java/com/example/antigravity/data/settings/SettingsRepository.kt`:
- Define `enum class ClientMode { NATIVE, REMOTE }`
- Implement `SettingsRepository` with getters/setters for mode, URL, workspace path with sensible defaults (`NATIVE`, `ws://127.0.0.1:8765`, null).

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.data.settings.SettingsRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit SettingsRepository**

```bash
git add app/src/main/java/com/example/antigravity/data/settings/SettingsRepository.kt app/src/test/java/com/example/antigravity/data/settings/SettingsRepositoryTest.kt
git commit -m "feat(settings): add ClientMode and SettingsRepository for mode and workspace persistence"
```

---

### Task 5: Client Factory & ChatViewModel Integration

**Files:**
- Create: `app/src/main/java/com/example/antigravity/data/client/AgyClientFactory.kt`
- Modify: `app/src/main/java/com/example/antigravity/ui/chat/ChatViewModel.kt`
- Modify: `app/src/test/java/com/example/antigravity/ui/chat/ChatViewModelTest.kt`

**Interfaces:**
- Consumes: `AgyClient`, `NativeAgyClient`, `WebSocketBridgeClient`, `SettingsRepository`, `WorkspaceManager`
- Produces:
  - `AgyClientFactory.createClient(...)`
  - `ChatViewModel.switchClientMode(mode: ClientMode)`
  - `ChatViewModel.updateWorkspacePath(path: String)`

- [ ] **Step 1: Write test in ChatViewModelTest for client switching and mode awareness**

Add unit tests in `ChatViewModelTest.kt` verifying:
- ViewModel initializes with provided client.
- ViewModel can switch to a new client smoothly when mode changes.
- Welcome message appropriately reflects native mode vs remote mode.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.ui.chat.ChatViewModelTest`
Expected: FAIL

- [ ] **Step 3: Implement AgyClientFactory and update ChatViewModel**

Create `AgyClientFactory.kt`.
Update `ChatViewModel.kt` to:
- Accept `clientFactory` / `currentMode`.
- Provide `switchMode(mode: ClientMode)`.
- Provide `updateWorkspace(path: String)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.antigravity.ui.chat.ChatViewModelTest`
Expected: PASS

- [ ] **Step 5: Commit client factory and ViewModel integration**

```bash
git add app/src/main/java/com/example/antigravity/data/client/AgyClientFactory.kt app/src/main/java/com/example/antigravity/ui/chat/ChatViewModel.kt app/src/test/java/com/example/antigravity/ui/chat/ChatViewModelTest.kt
git commit -m "feat(chat): integrate AgyClientFactory and dynamic client mode switching into ChatViewModel"
```

---

### Task 6: UI SettingsDialog & StatusHeader Enhancements

**Files:**
- Modify: `app/src/main/java/com/example/antigravity/ui/chat/components/SettingsDialog.kt`
- Modify: `app/src/main/java/com/example/antigravity/ui/chat/components/StatusHeader.kt`
- Modify: `app/src/main/java/com/example/antigravity/ui/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `ClientMode`, `BridgeStatus`, `WorkspaceManager`
- Produces:
  - Updated `SettingsDialog` with Mode radio buttons / tabs (Nativo Standalone vs Remoto Termux), Workspace Path input, and WebSocket URL input.
  - Updated `StatusHeader` indicating "Nativo Standalone" or "Termux Bridge" with respective status badge.

- [ ] **Step 1: Update SettingsDialog component**

Enhance `SettingsDialog.kt`:
- Add mode selector: `Nativo Local (Sem Termux)` vs `Remoto (Termux Bridge)`.
- Workspace directory input field (shows default if empty).
- WebSocket URL field (enabled/visible when in Remote mode).
- Pass callbacks for saving settings.

- [ ] **Step 2: Update StatusHeader component**

Enhance `StatusHeader.kt`:
- Display mode chip ("Nativo" / "Remoto").
- For native mode: Green = "Pronto (Nativo)", Yellow = "Executando...", Red = "Erro no binário".

- [ ] **Step 3: Connect settings in ChatScreen.kt**

Wire up the new settings dialog callbacks to ViewModel in `ChatScreen.kt`.

- [ ] **Step 4: Verify compilation and tests**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit UI updates**

```bash
git add app/src/main/java/com/example/antigravity/ui/chat/components/SettingsDialog.kt app/src/main/java/com/example/antigravity/ui/chat/components/StatusHeader.kt app/src/main/java/com/example/antigravity/ui/chat/ChatScreen.kt
git commit -m "feat(ui): update SettingsDialog and StatusHeader with client mode and workspace path support"
```

---

### Task 7: Full Verification & Documentation Update

**Files:**
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: All implemented components
- Produces: Green test suite, compiled APK artifact, updated architecture documentation

- [ ] **Step 1: Run all unit tests**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all unit tests passing.

- [ ] **Step 2: Assemble Debug APK**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`, output APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Update documentation in AGENTS.md and CLAUDE.md**

Update architecture diagrams, component inventory, and operational instructions to highlight the Standalone mode as primary with Termux as remote option.

- [ ] **Step 4: Commit documentation and complete phase**

```bash
git add AGENTS.md CLAUDE.md
git commit -m "docs: finalize architecture documentation for native standalone execution"
```
