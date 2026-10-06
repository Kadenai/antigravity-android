# Android integration validation

**Version:** 1.2.1 (versionCode 5), bridge protocol 4.
**Status:** implemented in code; final validation on Android with Termux and real AGY remains pending.

## Implemented behavior

After explicit initial setup, opening the app starts or reuses the local bridge through Termux `RUN_COMMAND` and waits for a confirmed AGY session before enabling prompts. Other compatible terminals support manual startup. The APK installs the Python bridge, while Python, AGY and authentication remain in the terminal.

Workspaces can be selected by path or terminal navigation, including authorized shared storage. The selection persists. Configuration confirms the folder through the terminal and CLI `init.cwd`; changing workspace/mode or starting a new conversation discards previous CLI context. Listener binding and `--reuse` avoid duplicate bridge processes.

Automatic mode starts disabled. Enabling it requires the installed CLI's `--dangerously-skip-permissions` support and `always-proceed` initialization confirmation. Default accept-edits does not grant unrestricted command approval. The validation button requires an actual `run_command` event and a read/write receipt; a text response is insufficient.

Preparation, connection, streaming, errors, logs, Stop, new conversations and recovery on return are implemented. The console displays logs; it is not a terminal emulator. Message editing rebuilds a session from earlier textual history without replaying old tools or undoing files. File cards represent checked terminal paths and send `@path` references without upload.

Terminal and AGY access must be verified independently from APK permissions. Android `content://` URIs are not CLI filesystem paths. Other apps' private directories remain restricted.

## Device checklist

1. Install the APK, Termux 0.109+, Python and a compatible AGY. Authenticate AGY, enable `allow-external-apps=true` and grant Termux command permission to the APK. Confirm the actual executable runs in the bridge's environment.
2. Enable automatic startup, stop the bridge and open the app. Confirm preparation, session readiness and a response without manual terminal commands. Reopen and check that no duplicate server or CLI process appears.
3. Select projects under `~/` and authorized shared storage after `termux-setup-storage`. Validate AGY reading, writing and command execution in both. Check the diagnostic for an inaccessible folder. Workspace changes must not mix files or conversation context.
4. Enable automatic mode and verify `always-proceed`, commands and edits without interactive CLI approval. Disable it and verify there is no silent unrestricted approval.
5. Send consecutive turns and multiline UTF-8 prompts. Stop a long task, send another prompt, start a new conversation and change workspace without retaining old context.
6. Return from the background, recreate the app and stop Termux. Verify recovery without duplicate processes. Check visible diagnostics for missing Python, missing/incompatible AGY, invalid login and denied permissions.
7. Send at least three requests, edit an intermediate request and resend. Later messages must disappear; the new response must use only earlier textual history, without replaying previous commands. Canceling must restore the draft and preserve history; editing must be blocked during a turn.
8. Select terminal/shared-storage files, including names with spaces and Unicode. Check cards, removal, references without accompanying text and reference preservation when editing. Ask AGY to read the files and verify content. Missing/inaccessible files and Android URIs must produce diagnostics without sandbox copies.

Record actual device results before declaring completion. Do not assume Android executable availability or restore the discarded `libantigravity.so`/Standalone design. This checklist supersedes the historical proposals in `docs/superpowers/`.

## Local verification and known issues

Prior verification passed 27 Android unit tests, 11 Python tests, `assembleDebug` and `lintDebug` (zero errors, 19 existing dependency/resource warnings). Tests cover event ordering, session negotiation, launch/reuse, persistence, editing, references, interruption, tool validation, executable lookup and bridge compatibility. CLI subprocesses in Python tests simulate the protocol.

Real AGY 1.2.16 on Windows confirmed CLI options and initialization events. This does not establish Android compatibility. A reported Termux installation of AGY 1.2.17 in `~/.local/bin/agy` motivated the executable lookup fallback; complete device validation is still open.

APK upgrades do not replace a running bridge. Protocol 4 rejects outdated bridges before configuration; stop the old process once before reopening the app. The fallback searches PATH first, then `~/.local/bin/agy`; an incompatible loader or another runtime environment cannot be fixed by executable lookup.

Exit code 137 indicates SIGKILL. It does not alone prove an `allow-external-apps` configuration failure. Check the actual diagnostic and battery restrictions when reconnection is needed.

Keep distributed APK, bridge and setup guide compatible. Contributor rules are in [AGENTS.md](../AGENTS.md); setup is in the [README](../README.md).
