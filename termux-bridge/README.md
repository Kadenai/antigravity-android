# Antigravity Android - Termux Bridge

Este script conecta o seu aplicativo Android nativo ao **Antigravity CLI** executado dentro do Termux.

---

## 🚀 Como Usar no Termux

### 1. Pré-requisitos no Termux
No Termux do seu celular Android, certifique-se de ter o Python e a biblioteca `websockets`:

```bash
pkg update && pkg install python -y
pip install websockets
```

### 2. Executar a Ponte
Baixe ou copie o arquivo `agy_bridge.py` para a pasta do seu projeto no Termux e execute:

```bash
python agy_bridge.py
```

O terminal exibirá:
```
============================================================
 Antigravity Android - Termux Bridge v0.0.1
 Servidor WebSocket iniciado em: ws://127.0.0.1:8765
============================================================
```

### 3. Abrir o Aplicativo Antigravity
1. Abra o aplicativo **Antigravity** no Android.
2. O aplicativo se conectará automaticamente a `ws://127.0.0.1:8765`.
3. O indicador superior ficará verde com o status: **"Conectado ao Termux"**.
4. Agora você pode digitar suas mensagens, prompts e comandos diretamente pela interface gráfica!

---

## 🛠️ Recursos da Versão 0.0.1
- **`antigravity --accept-edits`**: O Antigravity é iniciado automaticamente com auto-aceitação de edições para fluidez máxima.
- **Cancelamento Rápido (`Ctrl+C`)**: Botão de interrupção (Stop) no app para interromper tarefas em loop com envio de sinal `SIGINT`.
- **Streaming em Tempo Real**: Visualize a resposta do Antigravity surgindo token a token.
- **Renderização Markdown**: Títulos, listas, e blocos de código com destaque e botão de cópia de 1 clique.
- **Console do Terminal**: Botão no topo do app para inspecionar os logs brutos e saída ANSI caso queira conferir os detalhes técnicos.
