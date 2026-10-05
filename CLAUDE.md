# CLAUDE.md - Antigravity Android

Guia de contexto e comandos para agentes e desenvolvedores no projeto **Antigravity Android**.

---

## 📌 Visão Geral do Projeto

O **Antigravity Android** é um frontend nativo Android em **Jetpack Compose (Material 3)** projetado para substituir a experiência crua de terminal ao interagir com o **Antigravity CLI** no Android via Termux.

### Arquitetura de Comunicação
- **Frontend (Android App):** UI moderna de chat, suporte a Markdown, destaque de sintaxe em código, controle de fluxo (envio de prompt, interrupção via SIGINT) e visualizador de saída do terminal.
- **Camada de Cliente Unificada (`data/client/AgyClient.kt`):** Interface assíncrona que abstrai o motor de execução, suportando:
  1. **WebSocketBridgeClient:** Conexão remota com o Termux (`ws://127.0.0.1:8765`) ou PC via OkHttp.
  2. **NativeAgyClient:** Execução nativa standalone via `ProcessBuilder` (em andamento conforme a especificação em `docs/superpowers/specs/2026-10-05-native-standalone-architecture-design.md`).
- **Backend / Bridge (`termux-bridge/agy_bridge.py`):** Servidor WebSocket local (`ws://127.0.0.1:8765`) com **zero dependências externas** (apenas biblioteca padrão Python: `asyncio`, `hashlib`, `struct`).
- **Agente CLI:** Executado no Termux ou nativamente através do comando:
  ```bash
  antigravity --accept-edits
  ```

---

## 🛠️ Comandos Mais Usados

### Build e Testes (Gradle)
```powershell
# Executar testes unitários
.\gradlew.bat testDebugUnitTest

# Compilar o APK de Debug
.\gradlew.bat assembleDebug

# Build limpo e testes
.\gradlew.bat clean testDebugUnitTest assembleDebug

# Instalar no dispositivo conectado via ADB
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

### Executar a Ponte Termux
```bash
# No Termux (requer apenas python)
python termux-bridge/agy_bridge.py
```

---

## 📂 Estrutura de Diretórios

```
Antigravity Android/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/example/antigravity/
│   │       ├── MainActivity.kt               # Entrypoint da Activity com edge-to-edge
│   │       ├── Navigation.kt                 # Navegação principal exibindo ChatScreen
│   │       ├── data/
│   │       │   ├── client/
│   │       │   │   ├── AgyClient.kt          # Interface unificada para motor nativo ou WebSocket
│   │       │   │   ├── BridgeEvent.kt        # Eventos selados de streaming (Output, Status, TurnComplete)
│   │       │   │   ├── ProcessRunner.kt      # Abstrações de processo (AgyProcess, ProcessRunner)
│   │       │   │   ├── NativeAgyClient.kt    # Motor standalone nativo via ProcessBuilder
│   │       │   │   └── AgyClientFactory.kt   # Fábrica dinâmica de clientes (Nativo / Remoto)
│   │       │   ├── model/
│   │       │   │   ├── ChatMessage.kt        # Modelo de mensagem do chat e logs brutos
│   │       │   │   ├── BridgeStatus.kt       # Estados de conexão e do processo AGY
│   │       │   │   └── Protocol.kt           # DTOs serializáveis do protocolo WebSocket
│   │       │   ├── network/
│   │       │   │   ├── WebSocketBridgeClient.kt # Implementação AgyClient com OkHttp e auto-reconnect
│   │       │   │   └── AnsiCleaner.kt        # Sanitizador de sequências de escape ANSI e spinners
│   │       │   ├── settings/
│   │       │   │   └── SettingsRepository.kt # Persistência de preferências (modo, URL, workspace)
│   │       │   └── workspace/
│   │       │       └── WorkspaceManager.kt   # Gestão de diretórios de projetos (workspace)
│   │       ├── ui/chat/
│   │       │   ├── ChatScreen.kt             # Tela principal de chat
│   │       │   ├── ChatViewModel.kt          # Gerenciamento de estado, streaming e I/O via AgyClient
│   │       │   └── components/
│   │       │       ├── ChatBubble.kt         # Balões de mensagem com indicador pulsante
│   │       │       ├── ChatInputBar.kt       # Caixa de texto com botões Enviar / Parar (SIGINT)
│   │       │       ├── MarkdownContent.kt    # Renderizador de Markdown + blocos de código com cópia
│   │       │       ├── StatusHeader.kt       # Barra superior com chip de conexão dinâmico
│   │       │       ├── RawTerminalDialog.kt  # Modal de console com saída bruta do terminal
│   │       │       └── SettingsDialog.kt     # Diálogo de configuração de host/porta
│   │       └── theme/                        # Tema Material 3 (Color, Theme, Type)
│   └── src/test/java/com/example/antigravity/
│       ├── AnsiCleanerTest.kt                # Testes unitários do sanitizador ANSI
│       ├── data/
│       │   ├── client/NativeAgyClientTest.kt # Testes unitários do motor nativo
│       │   ├── settings/SettingsRepositoryTest.kt # Testes de persistência de configurações
│       │   └── workspace/WorkspaceManagerTest.kt  # Testes do gerenciador de workspace
│       └── ui/chat/
│           └── ChatViewModelTest.kt          # Testes unitários do ViewModel com FakeAgyClient
├── docs/superpowers/specs/
│   └── 2026-10-05-native-standalone-architecture-design.md # Especificação do motor nativo
├── termux-bridge/
│   ├── agy_bridge.py                         # Ponte WebSocket zero-deps em Python para Termux
│   └── README.md                             # Instruções de execução no Termux
├── gradle/libs.versions.toml                 # Version catalog (Compose, OkHttp, Kotlinx Serialization)
├── CLAUDE.md                                 # Guia rápido para Claude Code
└── AGENTS.md                                 # Instruções gerais para AI Agents
```

---

## 📡 Protocolo WebSocket (JSON sobre `ws://127.0.0.1:8765`)

### App ➔ Bridge:
- **Enviar prompt:**
  ```json
  { "type": "prompt", "content": "Seu prompt aqui" }
  ```
- **Interromper execução (SIGINT / Ctrl+C):**
  ```json
  { "type": "interrupt" }
  ```

### Bridge ➔ App:
- **Status do processo / conexão:**
  ```json
  { "type": "status", "state": "idle" | "running" | "interrupted" | "error", "message": "..." }
  ```
- **Pedaço de saída (stdout/stderr):**
  ```json
  { "type": "output", "stream": "stdout" | "stderr", "chunk": "..." }
  ```
- **Conclusão do turno:**
  ```json
  { "type": "turn_complete" }
  ```

---

## ⚠️ Regras e Boas Práticas

1. **Zero Dependências no Bridge (`agy_bridge.py`):**
   - Nunca adicione pacotes externos via `pip` ao script da ponte. Ele deve sempre rodar com **apenas a biblioteca padrão do Python** para evitar conflitos de ambiente no Termux (como PEP 668).
2. **Sanitização de ANSI:**
   - Toda saída crua recebida do terminal deve passar pelo `AnsiCleaner` antes de ser enviada para a UI do chat, mas deve ser preservada intacta em `rawLogs` para inspeção no `RawTerminalDialog`.
3. **Controle de Processo:**
   - Sempre respeitar a flag `--accept-edits` no Antigravity para evitar que prompts de confirmação interativos travem a execução sem que o usuário perceba.
4. **Toolchain Android no Ambiente Local:**
   - Java JBR: `C:\Program Files\Android\Android Studio\jbr`
   - Android SDK: `X:\Programs\Android\Sdk`
   - O `gradlew.bat` e `gradle.properties` estão configurados para detectar esses caminhos automaticamente.
