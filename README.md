# Antigravity Android

An Android chat frontend for the Antigravity CLI (AGY), built with Jetpack Compose and Material 3. AGY runs in your phone's terminal environment; the app connects through a local Python WebSocket bridge.

**Version:** 1.2.1 (versionCode 5). **Status:** local builds and tests verified; full Android validation with Termux and real AGY is still pending. The current app interface is in Portuguese; the usage documentation is in English.

```text
ChatScreen -> ChatViewModel -> WebSocketBridgeClient -> agy_bridge.py -> agy
                                                                     terminal workspace
```

The APK bundles the Python bridge, not AGY or Python. Files, tools, authentication and CLI permissions stay in the terminal. The app's console displays logs.

## Set up on your phone

1. Install and authenticate AGY in your terminal using the [official installation guide](https://antigravity.google/docs/cli/install/). Confirm `agy --version` works in the bridge's environment. Android/Termux compatibility still needs device validation; do not assume a Linux executable runs directly on Android.
2. In Termux, install Python with `pkg install python`. The bridge uses only the standard library, without pip packages.
3. For automatic startup with Termux 0.109+, enable external commands:

   ```sh
   mkdir -p ~/.termux
   printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
   termux-reload-settings
   ```

4. Build and install the APK (see below). In settings, enable **Iniciar ou reutilizar Termux ao abrir** (start or reuse Termux on launch), then grant permission to execute commands in Termux. The app installs the bridge in `~/.antigravity-android/`, starts it through `RUN_COMMAND` and connects to `ws://127.0.0.1:8765`. An existing compatible bridge is reused.
5. Enter an absolute workspace path or `~/project`, or browse terminal folders after connecting. Save the selection to keep it across launches. For shared storage, run `termux-setup-storage` and grant storage access to **Termux**. App permissions and Android `content://` URIs do not give the terminal direct filesystem access.
6. Optional: enable **Modo automático** (automatic mode). It starts disabled. The bridge verifies `--dangerously-skip-permissions` support and `permission_mode=always-proceed` in the CLI's `init` event. This allows commands and edits with the terminal's access; Android permissions and authentication remain necessary.
7. Use **Validar leitura, escrita e execução com AGY** (validate reading, writing and execution with AGY) to check actual tool access. This consumes one AGY turn. The bridge checks a read/write receipt and a real `run_command` event, then removes its temporary folder. A text response alone does not prove access.

Other compatible terminals can use [manual bridge startup](termux-bridge/README.md); disable automatic Termux startup in the app.

## Features and session behavior

- Streaming responses, Markdown, copyable code blocks and terminal logs.
- Persistent workspace selection, including terminal projects and authorized shared storage. Access is checked by the terminal and AGY; restrictions on other apps' private directories still apply.
- The paperclip browses terminal files and adds reference cards. Prompts contain `@path` references without uploading or copying files. The bridge checks readability; AGY's own permissions still apply.
- The pencil edits and resends a user message, removing later messages. A new CLI session receives only earlier textual history and the edited prompt. Earlier commands are not replayed; existing file changes and the old CLI conversation remain. Tool checkpoints are not restored. Canceling restores the previous draft; editing is disabled during a turn.
- Stop sends SIGINT to the process group on Android. The next prompt resumes by conversation ID. The **+** button starts a new conversation; changing workspace or mode resets CLI context after session confirmation.
- WebSocket reconnection, Ping/Pong, fragmented messages and multiline UTF-8 prompts. CLI, authentication and connection errors appear in chat and logs, including before the first prompt.
- The listener and app endpoint are restricted to loopback. Browser clients with an `Origin` header are rejected.

The default CLI command is:

```sh
agy --input-format stream-json --output-format stream-json --mode=accept-edits
```

The bridge streams `agent_response.text_delta` and completes each turn on `result`, following the [official headless protocol](https://antigravity.google/docs/cli/headless). `accept-edits` alone does not grant unrestricted command permissions.

## Troubleshooting

**AGY not found:** check `command -v agy`, `agy --version` and `agy --help` in the bridge's environment. Lookup searches `PATH`, then `~/.local/bin/agy`. Shell aliases are not Python-launchable executables. If AGY lives in another environment, run the bridge there too. An incompatible interpreter or loader cannot be fixed by changing `PATH`.

**Old bridge after an APK update:** updating the APK does not replace a running Python process. Version 1.2.1 requires protocol 4. Stop the old bridge with Ctrl+C, then reopen the app with automatic startup enabled. If no terminal session is accessible, Android Settings -> Apps -> Termux -> Force stop ends **all** Termux sessions; reopen Termux before the app. Manual users must update and restart the Python file. Active processes are not silently replaced.

**Exit code 137:** the process was forcibly terminated (SIGKILL). A message containing `on user request` means Termux reported a user-requested stop. Reconnect; if this happens unexpectedly, review Termux's battery restrictions. This code alone does not mean `allow-external-apps` failed.

**Background disconnects:** review Termux's battery restrictions and reconnect. Returning to the app attempts to restart the bridge if the connection is unavailable.

## Build and test

Use JDK 21 and Android SDK 36. Configure `JAVA_HOME` and your SDK location (`ANDROID_HOME` or an untracked `local.properties`). The project uses Gradle 9.1.0, AGP 9.0.1 and Kotlin 2.3.20; minimum Android version is 7.0 (SDK 24), with compile/target SDK 36.

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
python -m unittest discover -s termux-bridge -v
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
python -m unittest discover -s termux-bridge -v
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. The bridge asset is generated from `termux-bridge/agy_bridge.py`, keeping manual and automatic startup on the same source.

Prior local verification passed 27 Android unit tests and 11 Python tests, plus the debug build and lint (zero errors, 19 existing warnings). These checks do not replace [device validation](docs/PROXIMOS-PASSOS.md). CLI options and initialization events were checked with AGY 1.2.16 on Windows, which does not establish Android compatibility.

## Documentation

- [Bridge setup and protocol](termux-bridge/README.md)
- [Android validation checklist](docs/PROXIMOS-PASSOS.md)
- [Contributor instructions](AGENTS.md)
- [Termux RUN_COMMAND reference](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

Older proposals in `docs/superpowers/` are archived historical documents, partly in Portuguese. They have been superseded by the terminal bridge architecture and are not current implementation instructions.

## License

This project's source code is available under the [MIT License](LICENSE). AGY, Termux and other third-party components retain their own licenses and terms.
