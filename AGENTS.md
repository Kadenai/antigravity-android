# AGENTS.md - Antigravity Android

## Mission and architecture

Jetpack Compose / Material 3 frontend for AGY running in an Android terminal (Termux or another environment with AGY and Python).

Flow: `ChatScreen -> ChatViewModel -> WebSocketBridgeClient -> agy_bridge.py -> agy`.

The APK neither runs nor bundles the CLI. The workspace is selected and confirmed by the terminal and AGY's `init.cwd`; the bridge's current folder is the default when no path is supplied. Do not reintroduce Standalone mode, `libantigravity.so`, a mode factory or an app-sandbox workspace. The old plans in `docs/superpowers/` have been superseded.

`AgyClient` is the minimal contract used by the ViewModel and tests. `start`, `sendPrompt` and `interrupt` are suspend functions; `stop` is synchronous so resources can be released after viewModelScope cancellation.

## Current implementation and next validation

Version 1.2.1, versionCode 5. After explicit initial setup, the app can start the bridge through Termux `RUN_COMMAND`, reuse the session, connect the chat and offer automatic mode. The Python asset is generated from `termux-bridge/agy_bridge.py` and installed in `~/.antigravity-android/`. Other terminals use manual bridge startup.

Priority: [validate Android integration with Termux and real AGY](docs/PROXIMOS-PASSOS.md), including shared storage, permissions, commands, edits, cancellation and return from the background.

The flow is implemented in code; final device validation remains pending. Do not confuse the log console with an embedded terminal or mark the plan complete without that test. Reuse the existing bridge and check the real options of the installed CLI.

Support all user-provided workspaces accessible to the execution environment, in the terminal and authorized shared storage. Do not limit projects to the APK sandbox. Validate access through AGY; app permissions and Android picker URIs do not guarantee terminal access. Other apps' private directories remain subject to Android restrictions.

## Local protocol

Default endpoint: `ws://127.0.0.1:8765`.

App to bridge:

- `{"type":"prompt","content":"text"}`
- `{"type":"interrupt"}`
- `{"type":"configure","workspace":"~/project","automatic":false,"new_conversation":false}`
- `{"type":"workspaces","root":"~"}`
- `{"type":"files","root":"~/project"}` lists folders/files or verifies selected-file readability.
- `{"type":"prompt","content":"edited text","history":[{"role":"user","content":"earlier request"},{"role":"assistant","content":"earlier response"}]}` starts another session using only retained textual history. `history:[]` edits the first request; null preserves the session. Do not replay old turns or undo file changes.
- `{"type":"validate_workspace"}`

Bridge to app:

- `{"type":"status","state":"idle|running|interrupted|error|preparing","message":"..."}`
- `{"type":"output","stream":"stdout|stderr","chunk":"..."}`
- `{"type":"turn_complete"}`
- `{"type":"error","message":"..."}` for invalid messages.
- `{"type":"hello","protocol":4}` identifies the current bridge.
- `session` confirms `workspace`, `automatic`, `conversation_id`, `changed` and `cli_version`. Only session confirmation enables chat; workspace/mode changes or a new conversation discard previous CLI context.
- `workspaces` lists `root`, `parent` and `paths` in the terminal filesystem.
- `files` lists `root`, `parent`, `paths` and `files`, or returns `selected` with the canonical path of a readable file. References are UI cards and `@path` in the prompt, without upload.

The bridge starts `agy --input-format stream-json --output-format stream-json --mode=accept-edits`. Stdin prompts: `{"event":"user","message":{"content":"text"}}`, one JSON line per prompt. Use `agent_response.text_delta` for streaming and `result` to complete turns. Do not infer completion from `>` characters or process exit. Drain stdout/stderr before TurnComplete on unexpected exit.

References: [AGY headless protocol](https://antigravity.google/docs/cli/headless) and [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

## Rules

- **Zero external Python dependencies.** Never add pip packages. Must work with `pkg install python` and previously installed/authenticated AGY.
- Preserve indentation and UTF-8 in prompts and responses.
- Respond to Ping with Pong; accept fragmented WebSocket messages.
- Keep the listener on loopback; do not expose tool execution to the network.
- Authentication stays in the terminal CLI. Default mode is accept-edits. Explicit automatic mode requires `--dangerously-skip-permissions` in the installed CLI help and `always-proceed` confirmation in init. Never silently enable unrestricted permissions or confuse CLI and Android permissions.
- Errors must be visible in chat and logs, including before the first prompt.
- Android events are FIFO; only TurnComplete ends a normal response. Connection errors end streaming with a diagnostic.
- Keep dependencies light and run tests before finishing changes.

## Environment and verification

JDK 21 and Android SDK 36. Configure `JAVA_HOME` and the SDK location locally. Gradle 9.1.0, AGP 9.0.1, Kotlin 2.3.20; min SDK 24, compile/target SDK 36.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
python -m unittest discover -s termux-bridge -v
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

When updating a distributed APK, keep `agy_bridge.py` and its setup guide compatible. Local verification and the Android checklist are in [docs/PROXIMOS-PASSOS.md](docs/PROXIMOS-PASSOS.md).
