# AGENTS.md - Antigravity Android

Instruções técnicas de arquitetura, padrões e manutenção do projeto para agentes autônomos.

---

## 🎯 Missão do Projeto
O **Antigravity Android** fornece uma camada de interface moderna (Jetpack Compose / Material 3) para o **Antigravity CLI** executado dentro do Termux em dispositivos Android. 

Em vez de forçar o usuário a lidar com terminais em tela pequena (falta de formatação, códigos ANSI crus, quebra de linha inadequada, ausência de visualização de código), o aplicativo atua como um **cliente de chat local de alta performance**, delegando a execução pesada de ferramentas e comandos ao Termux.

---

## 🏗️ Arquitetura

O aplicativo suporta uma arquitetura modular baseada na interface `AgyClient`, desacoplando a camada de UI do motor de execução (Termux Bridge remoto ou Subprocesso Nativo Standalone):

```
┌─────────────────────────────────────────────────────────────┐
│                 Antigravity Android (APK)                   │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │   UI Layer (Jetpack Compose / Material 3)             │  │
│  │   - ChatScreen, ChatBubble, ChatInputBar              │  │
│  │   - MarkdownContent, StatusHeader, SettingsDialog     │  │
│  └───────────────────────────▲───────────────────────────┘  │
│                              │ StateFlow / Actions          │
│  ┌───────────────────────────▼───────────────────────────┐  │
│  │   ChatViewModel                                       │  │
│  └───────────────────────────▲───────────────────────────┘  │
│                              │ SharedFlow<BridgeEvent>      │
│  ┌───────────────────────────▼───────────────────────────┐  │
│  │   AgyClient (Interface Unificada)                     │  │
│  │   ├── WebSocketBridgeClient (Termux / PC Remoto)      │  │
│  │   └── NativeAgyClient (ProcessBuilder Standalone)     │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

A especificação completa da arquitetura nativa standalone está documentada em [docs/superpowers/specs/2026-10-05-native-standalone-architecture-design.md](docs/superpowers/specs/2026-10-05-native-standalone-architecture-design.md).

---

## 📋 Especificação do Protocolo WebSocket

A comunicação é local no endereço `ws://127.0.0.1:8765` usando mensagens JSON:

### Mensagens do App para o Bridge
| Campo | Tipo | Descrição |
|---|---|---|
| `type: "prompt"` | `String` | Envia um texto de usuário para o `stdin` do Antigravity. Campo: `content: String`. |
| `type: "interrupt"` | `String` | Envia sinal de interrupção (`SIGINT` / Ctrl+C) para abortar execução imediata. |

### Mensagens do Bridge para o App
| Campo | Tipo | Descrição |
|---|---|---|
| `type: "status"` | `String` | Notifica mudanças de estado: `state: "idle" \| "running" \| "interrupted" \| "error"`. |
| `type: "output"` | `String` | Transmite trecho (*chunk*) de saída do processo: `stream: "stdout" \| "stderr"`, `chunk: String`. |
| `type: "turn_complete"`| `String` | Indica que o ciclo de resposta atual terminou e o processo está pronto para o próximo comando. |

---

## ⚙️ Ambiente de Desenvolvimento Local

- **JDK / Java:** OpenJDK 21 (localizado no Android Studio JBR: `C:\Program Files\Android\Android Studio\jbr`)
- **Android SDK:** `X:\Programs\Android\Sdk`
- **Gradle:** 9.1.0 (via `gradlew.bat`)
- **Android Gradle Plugin (AGP):** 9.0.1
- **Kotlin:** 2.3.20
- **Compile / Target SDK:** 36 / 36
- **Min SDK:** 24 (Android 7.0+)

Configurações de ambiente já foram cravadas em `gradle.properties` e `gradlew.bat` com fallback automático.

---

## 🔨 Comandos de Verificação e Build

Sempre execute os testes e valide a compilação antes de finalizar qualquer alteração:

```powershell
# 1. Rodar os testes unitários da camada de dados/limpeza
.\gradlew.bat testDebugUnitTest

# 2. Compilar o APK de Debug
.\gradlew.bat assembleDebug

# 3. Localização do APK gerado
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 🧩 Componentes Principais

### Frontend Android (`app/src/main/java/com/example/antigravity/`)
- **`data/client/AgyClient.kt`**: Interface unificada de ciclo de vida (`start`, `sendPrompt`, `interrupt`, `stop`) para qualquer motor AGY.
- **`data/client/BridgeEvent.kt`**: Definições seladas de eventos (`Output`, `Status`, `TurnComplete`, `Error`).
- **`data/client/ProcessRunner.kt`**: Abstrações para processos (`AgyProcess`, `ProcessRunner`).
- **`data/client/NativeAgyClient.kt`**: Motor de execução nativo standalone (`ProcessBuilder`, `SIGINT`, I/O streaming assíncrono).
- **`data/client/AgyClientFactory.kt`**: Factory dinâmica de clientes de acordo com o `ClientMode` (Nativo vs Remoto).
- **`data/network/WebSocketBridgeClient.kt`**: Implementação de `AgyClient` via WebSocket RFC 6455 com reconexão resiliente.
- **`data/network/AnsiCleaner.kt`**: Remove sequências CSI, OSC e trata caracteres de retorno de carro (`\r`) típicos de spinners de terminal.
- **`data/workspace/WorkspaceManager.kt`**: Gerenciador de diretório de trabalho (workspace padrão interno `context.filesDir/workspace` e customizado).
- **`data/settings/SettingsRepository.kt`**: Gerenciador de preferências do app (Modo Nativo/Remoto, URL, Workspace).
- **`ui/chat/ChatViewModel.kt`**: Acumulador de estado, gerenciador do ciclo de streaming das mensagens, alternância dinâmica de modo e desacoplado via `AgyClient`.
- **`ui/chat/ChatScreen.kt`**: Tela raiz com `Scaffold`, lista de mensagens com auto-scroll reativo, cabeçalho e campo de entrada.
- **`ui/chat/components/MarkdownContent.kt`**: Parser de Markdown leve que renderiza cabeçalhos, marcadores, formatação inline e blocos de código com botão de cópia de um toque.
- **`ui/chat/components/ChatBubble.kt`**: Balões com distinção visual entre usuário e IA, indicador pulsante durante o streaming e atalho para ver a saída crua do terminal.
- **`ui/chat/components/ChatInputBar.kt`**: Entrada de texto com botão contextual: **Enviar** (quando ocioso) ou **Parar** (quando o AGY estiver trabalhando).
- **`ui/chat/components/SettingsDialog.kt`**: Diálogo para escolha de Modo de Execução (Nativo Standalone vs Remoto Termux), URL e caminho do Workspace.
- **`ui/chat/components/StatusHeader.kt`**: Barra superior com status dinâmico de acordo com o modo ativo.

### Backend Bridge (`termux-bridge/`)
- **`agy_bridge.py`**:
  - Servidor WebSocket RFC 6455 escrito à mão usando exclusivamente a biblioteca padrão do Python.
  - **REQUISITO MANDATÓRIO:** Nunca utilizar pacotes instalados por `pip`. Deve rodar com zero dependências externas.
  - Executa o Antigravity com a flag `--accept-edits`.

---

## 📌 Diretrizes para Agentes Futuros
1. **Evite inchar dependências:** Mantenha o app leve e com foco em usabilidade mobile.
2. **Preserve a compatibilidade do Bridge:** O script em Python precisa ser copiável e executável em qualquer instalação fresca do Termux (`pkg install python`).
3. **Não remova os testes unitários:** Mantenha `AnsiCleanerTest` sempre atualizado caso novas sequências ANSI sejam tratadas.
