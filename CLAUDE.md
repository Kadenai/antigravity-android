# CLAUDE.md - Antigravity Android

Read [AGENTS.md](AGENTS.md) for architecture, protocol rules and verification commands, and [README.md](README.md) for setup and usage.

The app is a frontend for AGY in an Android terminal. The local Python bridge uses stream-json and only the standard library. The workspace is selected and confirmed by the terminal and AGY's `init.cwd`; the bridge's current folder is the default. The APK does not contain a standalone CLI runtime.

Version 1.2.1 implements Termux `RUN_COMMAND` startup, bridge reuse, persistent workspace selection, file references and message editing. Python, AGY and authentication stay in the terminal; other terminals use manual startup.

Automatic mode is disabled by default and requires explicit selection, CLI support for `--dangerously-skip-permissions` and `always-proceed` confirmation in `init`. Workspace validation requires a read/write receipt and actual `run_command` event.

Local builds, lint and tests have passed. [Full Android validation with Termux and real AGY](docs/PROXIMOS-PASSOS.md) remains pending. Do not mark it complete or treat archived standalone proposals as current instructions.
