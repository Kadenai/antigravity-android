> HISTÓRICO: proposta substituída em 2026-10-05. O aplicativo é um frontend do AGY no terminal Android por uma ponte local. Consulte AGENTS.md e README.md.

> Implementação vigente: APK 1.1.0 com inicialização pelo Termux, ponte local e seleção de workspaces; [validação em Android com AGY real pendente](../../PROXIMOS-PASSOS.md). As afirmações sobre binário ARM64 e execução Standalone abaixo pertencem à proposta descartada, não são comprovação de suporte Android e não autorizam restaurar esse modo.

# Especificação de Design: Arquitetura Nativa Standalone (Sem Dependência do Termux)

**Data:** 2026-10-05  
**Status:** Arquivado; substituído pelo plano de integração automática com o terminal Android.
**Componente:** Antigravity Android (`app/`)

---

## 1. Visão Geral e Motivação

Atualmente, o **Antigravity Android** opera como um cliente de interface que depende do aplicativo externo **Termux** para rodar um script intermediário em Python (`termux-bridge/agy_bridge.py`) via WebSocket na porta 8765. 

Com a disponibilização de binários ARM64 nativos do **Antigravity CLI** para Android, essa dependência externa torna-se desnecessária. O objetivo deste projeto é transformar o Antigravity Android em um aplicativo **100% autônomo (standalone)**:
- Eliminar a necessidade de instalar e configurar o Termux.
- Eliminar o servidor intermediário em Python e o tráfego de rede local (loopback WebSocket).
- Executar o processo do Antigravity CLI diretamente dentro da sandbox do app Android através de `ProcessBuilder` nativo em Kotlin com controle assíncrono de I/O via Coroutines.

---

## 2. Arquitetura do Sistema

```
┌─────────────────────────────────────────────────────────────┐
│                 Antigravity Android (APK)                   │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │   UI Layer (Jetpack Compose / Material 3)             │  │
│  │   - ChatScreen, ChatBubble, ChatInputBar              │  │
│  │   - MarkdownContent, SettingsDialog                   │  │
│  └───────────────────────────▲───────────────────────────┘  │
│                              │ StateFlow / Actions          │
│  ┌───────────────────────────▼───────────────────────────┐  │
│  │   ChatViewModel                                       │  │
│  └───────────────────────────▲───────────────────────────┘  │
│                              │ Flow<BridgeEvent>            │
│  ┌───────────────────────────▼───────────────────────────┐  │
│  │   AgyClient (Interface Unificada)                     │  │
│  │   ├── NativeAgyClient (Padrão: ProcessBuilder Local)  │  │
│  │   └── WebSocketBridgeClient (Opcional: PC Remoto)     │  │
│  └───────────────────────────▲───────────────────────────┘  │
│                              │                              │
│         ┌────────────────────┴────────────────────┐         │
│         │ stdin / stdout / stderr (Pipes I/O)     │         │
│         ▼                                         │         │
│  ┌──────────────────────────────────────────────┐ │         │
│  │ Subprocesso Antigravity CLI                  │ │         │
│  │ (app/src/main/jniLibs/arm64-v8a/             │ │         │
│  │  libantigravity.so)                          │ │         │
│  │                                              │ │         │
│  │ - CWD: context.filesDir/workspace            │ │         │
│  │ - HOME: context.filesDir                     │ │         │
│  │ - Shell: /system/bin/sh (Toybox nativo)      │ │         │
│  └──────────────────────────────────────────────┘ │         │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Especificação Técnica dos Componentes

### 3.1. Empacotamento e Execução do Binário Nativo
- **Localização no Projeto:** `app/src/main/jniLibs/arm64-v8a/libantigravity.so`
- **Mecanismo de Permissão Android (W^X / SELinux):** No Android 10+ (API 29+), arquivos executáveis não podem rodar a partir do armazenamento interno de dados comum (`/data/data/.../files/`). Ao nomear o binário como uma biblioteca compartilhada (`libantigravity.so`) e posicioná-lo em `jniLibs`, o gerenciador de pacotes do Android instala o arquivo em `applicationInfo.nativeLibraryDir` com atributos de permissão de execução legítimos (`chmod +x`).
- **Configuração no Manifesto / Gradle:**
  - Garantir `android:extractNativeLibs="true"` na tag `<application>` do `AndroidManifest.xml`.
  - Configurar `ndk.abiFilters += listOf("arm64-v8a")` (com suporte a fallback ou emuladores quando aplicável).

### 3.2. Abstração de Comunicação (`AgyClient`)
Criar uma interface que abstrai o provedor de execução do agente:

```kotlin
sealed interface BridgeEvent {
    data class Output(val stream: StreamType, val chunk: String) : BridgeEvent
    data class Status(val state: BridgeState, val message: String) : BridgeEvent
    data object TurnComplete : BridgeEvent
}

enum class StreamType { STDOUT, STDERR }

interface AgyClient {
    val status: StateFlow<BridgeStatus>
    val incomingEvents: SharedFlow<BridgeEvent>
    
    suspend fun start()
    suspend fun sendPrompt(prompt: String)
    suspend fun interrupt()
    suspend fun stop()
}
```

### 3.3. Implementação da Engine Local (`NativeAgyClient`)
- **Inicialização do Processo:**
  - Resolução do caminho: `File(context.applicationInfo.nativeLibraryDir, "libantigravity.so")`.
  - Disparo via `ProcessBuilder`:
    ```kotlin
    val pb = ProcessBuilder(binaryFile.absolutePath, "--accept-edits")
    pb.directory(workspaceDir)
    pb.environment().apply {
        put("HOME", context.filesDir.absolutePath)
        put("TMPDIR", context.cacheDir.absolutePath)
        put("PATH", "/system/bin:/system/xbin:" + context.applicationInfo.nativeLibraryDir)
        put("LANG", "en_US.UTF-8")
        put("LC_ALL", "en_US.UTF-8")
    }
    val process = pb.start()
    ```
- **Fluxos Assíncronos de Leitura:**
  - Duas rotinas de Coroutines em `Dispatchers.IO` para leitura de `process.inputStream` e `process.errorStream`.
  - Buffers de leitura contínua (512 bytes / UTF-8) emitindo eventos `BridgeEvent.Output` no `incomingEvents`.
- **Envio de Prompt (stdin):**
  - Escrita direta no `process.outputStream` com quebra de linha `\n` e `flush()`.
  - Transição do estado para `BridgeStatus.Running`.
- **Interrupção de Execução (SIGINT):**
  - Utilização do PID do processo e envio do sinal nativo `SIGINT` (Ctrl+C):
    ```kotlin
    val pid = process.pid() // Java 9+ / Android
    android.system.Os.kill(pid.toInt(), android.system.OsConstants.SIGINT)
    ```
- **Detecção de Fim de Turno:**
  - Monitoramento de marcadores de prompt pronto na saída do Antigravity CLI e emissão de `BridgeEvent.TurnComplete`.

### 3.4. Gerenciamento do Workspace (`WorkspaceManager`)
- **Workspace Padrão:** `File(context.filesDir, "workspace")`, criado automaticamente se não existir.
- **Workspace Externo:** Opcional, permitindo apontar para `/storage/emulated/0/Projects/` quando a permissão `MANAGE_EXTERNAL_STORAGE` estiver concedida.
- **Configuração no `SettingsDialog`:**
  - Modo de Operação: `Nativo Local (Sem Termux)` ou `Remoto (WebSocket)`.
  - Caminho do Diretório de Trabalho (Workspace Path).

---

## 4. Tratamento de Erros e Casos de Borda

1. **Binário não suportado na arquitetura atual (ex: emulador x86_64):**
   - O `NativeAgyClient` detecta a ausência do binário para a ABI atual e emite `BridgeStatus.Error` com mensagem informativa: *"Binário nativo não disponível para a arquitetura x86_64. Use o modo remoto ou execute em um dispositivo ARM64."*
2. **Crash ou encerramento inesperado do processo:**
   - A corrotina de monitoramento detecta o encerramento do processo (`process.waitFor()`) e emite o status `BridgeStatus.Idle(exitCode)`, reabrindo o processo automaticamente no próximo prompt.
3. **Persistência de Sessão e Auth:**
   - Como o `HOME` aponta para `context.filesDir`, as credenciais e configurações de autenticação criadas pelo Antigravity em `~/.gemini/` permanecem salvas entre reinicializações do aplicativo.

---

## 5. Estratégia de Testes

- **Testes Unitários da Camada de Rede / Processo:**
  - Testes com mocks de `InputStream`/`OutputStream` para validar o pipeline assíncrono de chunks no `NativeAgyClient`.
  - Teste de serialização e estados no `ChatViewModel`.
- **Sanitização de Saída:**
  - Validação contínua com `AnsiCleanerTest` para garantir que sequências ANSI produzidas pelo binário nativo sejam filtradas sem perder texto legível.
- **Build e Empacotamento:**
  - Verificação de compilação com `.\gradlew.bat testDebugUnitTest` e `.\gradlew.bat assembleDebug`.

---

## 6. Plano de Execução em Fases

1. **Fase 1: Abstração de Cliente e Eventos**
   - Criar `AgyClient` e DTOs de eventos.
   - Refatorar `ChatViewModel` para depender de `AgyClient`.
   - Adaptar `WebSocketBridgeClient` para implementar `AgyClient`.
2. **Fase 2: Motor de Processo Nativo (`NativeAgyClient`)**
   - Implementar `NativeAgyClient` com `ProcessBuilder` e Coroutines.
   - Implementar tratamento de `stdin`, `stdout`, `stderr` e sinais `SIGINT`.
3. **Fase 3: Configurações de Build e Estrutura de Arquivos Nativa**
   - Criar estrutura `jniLibs/arm64-v8a/` no app.
   - Ajustar `AndroidManifest.xml` e `build.gradle.kts` para extração de bibliotecas nativas.
4. **Fase 4: Workspace e Atualizações de UI**
   - Implementar `WorkspaceManager`.
   - Adicionar chave de seleção de modo e workspace no `SettingsDialog`.
5. **Fase 5: Testes Unitários e Validação Final**
   - Implementar testes para `NativeAgyClient`.
   - Validar testes existentes e compilar APK.
   - Atualizar documentações (`CLAUDE.md`, `AGENTS.md`).
