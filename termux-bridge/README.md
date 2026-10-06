# Antigravity Android local bridge

Connects the app to **AGY installed in the phone's terminal environment**. APK 1.2.1 requires protocol 4. Python and an authenticated `agy` executable must be available in the same environment. Only the standard library is used; full Android validation is still pending.

For automatic Termux startup, follow the [main README](../README.md). The APK installs this source in `~/.antigravity-android/` through `RUN_COMMAND`; Python, AGY, authentication and Android permissions are initial setup requirements.

## Manual startup

1. Install AGY and authenticate by running `agy`. See the [official installation guide](https://antigravity.google/docs/cli/install/).
2. In Termux, run `pkg install python`.
3. Copy `agy_bridge.py` into the terminal environment and start it from your workspace:

   ```sh
   cd /path/to/project
   python /path/to/bridge/agy_bridge.py
   ```

4. Disable automatic Termux startup in the app and connect to `ws://127.0.0.1:8765`. Keep the terminal running.

The current folder is the initial workspace. Any user-provided path accessible to the terminal can be selected, including authorized shared storage. The bridge starts:

```sh
agy --input-format stream-json --output-format stream-json --mode=accept-edits
```

NDJSON preserves indentation, newlines and UTF-8. The bridge forwards `agent_response.text_delta` and completes turns on `result`. A final response is used only if no deltas arrived. SIGINT interrupts execution; the next prompt resumes by conversation ID.

The server listens only on loopback and rejects browser clients with an `Origin` header. Authentication and tool permissions belong to AGY. `accept-edits` does not grant unrestricted command access.

## Startup and protocol

`python agy_bridge.py --reuse` reuses a compatible bridge on port 8765. Binding happens before CLI startup to avoid duplicate processes. `/health` identifies the bridge and protocol; unrelated services and outdated bridges produce an error.

On connection, the bridge sends `{"type":"hello","protocol":4}` and its latest status. The app waits for confirmed `session` before enabling prompts.

- `{"type":"configure","workspace":"~/project","automatic":false,"new_conversation":false}` checks the folder, CLI options and `init.cwd`. Returns `session` with canonical workspace, mode, conversation ID, change flag and CLI version. Workspace/mode changes or a new conversation discard previous CLI context. Changes are rejected during a turn.
- `{"type":"workspaces","root":"~"}` lists terminal folders with `root`, `parent` and `paths`. Absolute paths can reach authorized shared storage; Android URIs are not filesystem paths.
- `{"type":"files","root":"~/project"}` lists `paths` and `files`, with `root` and `parent`. A file root is checked for readability and returned as a canonical `selected` path. Cards represent `@path` references, without copying or uploading files; AGY permissions still apply.
- `{"type":"prompt","content":"text"}` sends a prompt; `{"type":"interrupt"}` cancels preparation or the current turn.
- `{"type":"validate_workspace"}` runs an AGY turn reading temporary input and writing a receipt through Python. The bridge checks the receipt and real `run_command` event, removes temporary files and reports the result. Denied tools do not pass.

Automatic mode starts disabled. `automatic=true` requires `--dangerously-skip-permissions` in the installed CLI help and `permission_mode=always-proceed` in `init`.

`prompt` optionally accepts `history`, a list of objects with `role` (`user` or `assistant`) and `content`. Missing/null history keeps the session; `[]` starts another session to edit the first prompt. A list starts a new session with one turn containing earlier textual history and the current prompt. Earlier tools are not replayed; file edits and the old stored conversation remain. Tool checkpoints are not restored.

Bridge events include `status` (`idle`, `running`, `interrupted`, `error`, `preparing`), `output` (`stdout`/`stderr` chunks), `turn_complete`, `error`, `hello`, `session`, `workspaces` and `files`. Android processes events in FIFO order; only `turn_complete` ends a normal response. Connection errors end streaming with a diagnostic. Unexpected CLI exits drain stdout/stderr before completion.

## Troubleshooting and updates

Check `command -v agy`, `agy --version` and `agy --help` in the bridge's environment. Lookup prioritizes `PATH`, then `~/.local/bin/agy`. Shell aliases cannot be launched by Python. If the CLI lives in another environment, run the bridge there too. Android compatibility remains unverified; an incompatible Linux loader is not fixed by changing the path.

Protocol 4 includes the executable fallback. The APK refuses earlier bridges before configuration; `--reuse` rejects older health responses. Stop an old bridge before reopening an updated APK. Use Ctrl+C, or Android's Force stop for Termux if necessary (ends all Termux sessions), then reopen Termux and the app. Manual users must update and restart the Python file.

The APK does not install AGY or transfer credentials. Errors appear in chat and logs. Keep distributed APK, bridge and setup guide versions compatible.

## Verification

From the repository root:

```sh
python -m unittest discover -s termux-bridge -v
```

Tests use simulated CLI subprocesses without AGY or external networking. Prior verification passed 11 Python tests, including interruption, UTF-8, mode checks, workspace confirmation, tool validation, listener reuse, file selection, edited history and executable lookup. Windows AGY 1.2.16 confirmed options and initialization events; Android validation remains open.

See the [device checklist](../docs/PROXIMOS-PASSOS.md), [headless protocol](https://antigravity.google/docs/cli/headless) and [Termux integration](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
