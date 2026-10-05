#!/usr/bin/env python3
"""
Antigravity Android - Termux WebSocket Bridge (v0.0.1)

Executa o Antigravity CLI com `--accept-edits` e expõe uma interface WebSocket em
localhost:8765 para o aplicativo Android do Antigravity.

Como rodar no Termux:
    pkg install python
    pip install websockets
    python agy_bridge.py
"""

import asyncio
import json
import os
import signal
import sys

try:
    import websockets
except ImportError:
    print("A biblioteca 'websockets' não está instalada.")
    print("Instale com: pip install websockets")
    sys.exit(1)

HOST = "127.0.0.1"
PORT = 8765

# Comando para iniciar o Antigravity
AGY_COMMAND = ["antigravity", "--accept-edits"]


class AntigravitySession:
    def __init__(self):
        self.process = None
        self.connected_clients = set()
        self.is_running = False

    async def start_process(self):
        """Inicia o processo do Antigravity CLI."""
        if self.process is not None and self.process.returncode is None:
            return

        print(f"[AGY Bridge] Iniciando processo: {' '.join(AGY_COMMAND)}")
        try:
            self.process = await asyncio.create_subprocess_exec(
                *AGY_COMMAND,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            self.is_running = False
            asyncio.create_task(self._read_stream(self.process.stdout, "stdout"))
            asyncio.create_task(self._read_stream(self.process.stderr, "stderr"))
            asyncio.create_task(self._monitor_process())
        except FileNotFoundError:
            err_msg = (
                "[AGY Bridge Erro] Comando 'antigravity' não encontrado no PATH.\n"
                "Certifique-se de que o Antigravity está instalado no Termux."
            )
            print(err_msg)
            await self.broadcast({
                "type": "output",
                "stream": "stderr",
                "chunk": err_msg + "\n"
            })
            await self.broadcast({
                "type": "status",
                "state": "error",
                "message": "Comando antigravity não encontrado"
            })

    async def _read_stream(self, stream, stream_name):
        """Lê os dados da saída do Antigravity e retransmite para os clientes conectados."""
        buffer = bytearray()
        while True:
            try:
                chunk = await stream.read(512)
                if not chunk:
                    break
                text = chunk.decode("utf-8", errors="replace")
                await self.broadcast({
                    "type": "output",
                    "stream": stream_name,
                    "chunk": text
                })
            except Exception as e:
                print(f"[AGY Bridge] Erro na leitura do stream {stream_name}: {e}")
                break

    async def _monitor_process(self):
        """Monitora término do processo."""
        if not self.process:
            return
        returncode = await self.process.wait()
        print(f"[AGY Bridge] Processo do Antigravity encerrou com código {returncode}")
        self.is_running = False
        await self.broadcast({
            "type": "status",
            "state": "idle",
            "message": f"Processo encerrado (exit code {returncode})"
        })
        await self.broadcast({"type": "turn_complete"})
        self.process = None

    async def send_prompt(self, prompt: str):
        """Envia um prompt para o stdin do Antigravity."""
        if self.process is None or self.process.returncode is not None:
            await self.start_process()

        if self.process and self.process.stdin:
            print(f"[AGY Bridge] Enviando prompt: {prompt[:60]}...")
            self.is_running = True
            await self.broadcast({
                "type": "status",
                "state": "running",
                "message": "Antigravity processando..."
            })
            clean_prompt = prompt.strip() + "\n"
            self.process.stdin.write(clean_prompt.encode("utf-8"))
            await self.process.stdin.drain()

    async def send_interrupt(self):
        """Envia SIGINT (Ctrl+C) para interromper o processo atual."""
        if self.process and self.process.returncode is None:
            print("[AGY Bridge] Enviando SIGINT para o Antigravity...")
            try:
                self.process.send_signal(signal.SIGINT)
                self.is_running = False
                await self.broadcast({
                    "type": "status",
                    "state": "interrupted",
                    "message": "Execução cancelada pelo usuário"
                })
                await self.broadcast({"type": "turn_complete"})
            except Exception as e:
                print(f"[AGY Bridge] Erro ao enviar sinal: {e}")

    async def register_client(self, websocket):
        self.connected_clients.add(websocket)
        state_str = "running" if self.is_running else "idle"
        welcome_msg = {
            "type": "status",
            "state": state_str,
            "message": "Conectado à ponte do Termux"
        }
        await websocket.send(json.dumps(welcome_msg))

    def unregister_client(self, websocket):
        self.connected_clients.discard(websocket)

    async def broadcast(self, data: dict):
        if not self.connected_clients:
            return
        payload = json.dumps(data)
        disconnected = set()
        for client in self.connected_clients:
            try:
                await client.send(payload)
            except Exception:
                disconnected.add(client)
        for client in disconnected:
            self.connected_clients.discard(client)


session = AntigravitySession()


async def handler(websocket):
    await session.register_client(websocket)
    print(f"[AGY Bridge] Novo cliente conectado: {websocket.remote_address}")
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                msg_type = data.get("type")
                if msg_type == "prompt":
                    content = data.get("content", "")
                    await session.send_prompt(content)
                elif msg_type == "interrupt":
                    await session.send_interrupt()
                else:
                    print(f"[AGY Bridge] Mensagem não reconhecida: {message}")
            except json.JSONDecodeError:
                # Trata como prompt direto se for texto simples
                await session.send_prompt(message)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        session.unregister_client(websocket)
        print("[AGY Bridge] Cliente desconectado")


async def main():
    print("=" * 60)
    print(" Antigravity Android - Termux Bridge v0.0.1")
    print(f" Servidor WebSocket iniciado em: ws://{HOST}:{PORT}")
    print("=" * 60)
    await session.start_process()

    async with websockets.serve(handler, HOST, PORT):
        await asyncio.Future()  # Mantém servidor rodando indefinidamente


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[AGY Bridge] Encerrando servidor bridge.")
