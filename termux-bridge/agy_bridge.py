#!/usr/bin/env python3
"""
Antigravity Android - Termux Bridge v0.0.1 (Zero Dependencies)

Servidor WebSocket nativo implementado com a BIBLIOTECA PADRÃO do Python.
NÃO precisa de 'pip install' nem 'pkg install websockets'.
Roda diretamente com:
    python agy_bridge.py
"""

import asyncio
import base64
import hashlib
import json
import os
import signal
import struct
import sys

HOST = "127.0.0.1"
PORT = 8765
AGY_COMMAND = ["antigravity", "--accept-edits"]
WS_MAGIC = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def make_handshake_response(key: str) -> bytes:
    accept_raw = hashlib.sha1(key.encode("utf-8") + WS_MAGIC).digest()
    accept_b64 = base64.b64encode(accept_raw).decode("utf-8")
    return (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept_b64}\r\n\r\n"
    ).encode("utf-8")


def encode_ws_frame(message: str) -> bytes:
    data = message.encode("utf-8")
    length = len(data)
    if length <= 125:
        header = struct.pack("!BB", 0x81, length)
    elif length <= 65535:
        header = struct.pack("!BBH", 0x81, 126, length)
    else:
        header = struct.pack("!BBQ", 0x81, 127, length)
    return header + data


async def read_ws_frame(reader: asyncio.StreamReader):
    try:
        header = await reader.readexactly(2)
    except asyncio.IncompleteReadError:
        return None

    opcode = header[0] & 0x0F
    is_masked = bool(header[1] & 0x80)
    payload_len = header[1] & 0x7F

    if opcode == 0x8:  # Connection Close
        return None

    if payload_len == 126:
        ext = await reader.readexactly(2)
        payload_len = struct.unpack("!H", ext)[0]
    elif payload_len == 127:
        ext = await reader.readexactly(8)
        payload_len = struct.unpack("!Q", ext)[0]

    mask = await reader.readexactly(4) if is_masked else None
    payload = await reader.readexactly(payload_len)

    if is_masked and mask:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

    if opcode == 0x9:  # Ping frame
        return ""

    return payload.decode("utf-8", errors="replace")


class AntigravitySession:
    def __init__(self):
        self.process = None
        self.clients = set()
        self.is_running = False

    async def start_process(self):
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
            asyncio.create_task(self._monitor())
        except FileNotFoundError:
            err = (
                "[AGY Bridge Erro] Comando 'antigravity' não encontrado no PATH.\n"
                "Certifique-se de que o Antigravity CLI está instalado no Termux."
            )
            print(err)
            await self.broadcast({
                "type": "output",
                "stream": "stderr",
                "chunk": err + "\n"
            })
            await self.broadcast({
                "type": "status",
                "state": "error",
                "message": "Comando antigravity não encontrado"
            })

    async def _read_stream(self, stream, name):
        while True:
            try:
                chunk = await stream.read(512)
                if not chunk:
                    break
                text = chunk.decode("utf-8", errors="replace")
                await self.broadcast({
                    "type": "output",
                    "stream": name,
                    "chunk": text
                })
            except Exception:
                break

    async def _monitor(self):
        if not self.process:
            return
        code = await self.process.wait()
        print(f"[AGY Bridge] Processo encerrou com código {code}")
        self.is_running = False
        await self.broadcast({
            "type": "status",
            "state": "idle",
            "message": f"Processo encerrado (code {code})"
        })
        await self.broadcast({"type": "turn_complete"})
        self.process = None

    async def send_prompt(self, prompt: str):
        if self.process is None or self.process.returncode is not None:
            await self.start_process()

        if self.process and self.process.stdin:
            print(f"[AGY Bridge] Prompt recebido: {prompt[:50]}...")
            self.is_running = True
            await self.broadcast({
                "type": "status",
                "state": "running",
                "message": "Antigravity processando..."
            })
            self.process.stdin.write((prompt.strip() + "\n").encode("utf-8"))
            await self.process.stdin.drain()

    async def send_interrupt(self):
        if self.process and self.process.returncode is None:
            print("[AGY Bridge] Enviando cancelamento (SIGINT)...")
            try:
                self.process.send_signal(signal.SIGINT)
                self.is_running = False
                await self.broadcast({
                    "type": "status",
                    "state": "interrupted",
                    "message": "Execução cancelada"
                })
                await self.broadcast({"type": "turn_complete"})
            except Exception as e:
                print(f"[AGY Bridge] Falha ao enviar sinal: {e}")

    async def broadcast(self, data: dict):
        if not self.clients:
            return
        frame = encode_ws_frame(json.dumps(data))
        dead_clients = set()
        for writer in self.clients:
            try:
                writer.write(frame)
                await writer.drain()
            except Exception:
                dead_clients.add(writer)
        for w in dead_clients:
            self.clients.discard(w)


session = AntigravitySession()


async def handle_connection(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    # 1. Leitura do cabeçalho HTTP Handshake
    headers = b""
    while b"\r\n\r\n" not in headers:
        line = await reader.readline()
        if not line:
            writer.close()
            return
        headers += line

    headers_str = headers.decode("utf-8", errors="replace")
    ws_key = None
    for h in headers_str.split("\r\n"):
        if h.lower().startswith("sec-websocket-key:"):
            ws_key = h.split(":", 1)[1].strip()
            break

    if not ws_key:
        writer.write(b"HTTP/1.1 400 Bad Request\r\n\r\n")
        await writer.drain()
        writer.close()
        return

    # 2. Resposta de Handshake 101 Switching Protocols
    writer.write(make_handshake_response(ws_key))
    await writer.drain()

    # 3. Registra cliente e envia status inicial
    session.clients.add(writer)
    addr = writer.get_extra_info("peername")
    print(f"[AGY Bridge] Novo cliente conectado: {addr}")

    init_msg = {
        "type": "status",
        "state": "running" if session.is_running else "idle",
        "message": "Conectado à ponte do Termux"
    }
    writer.write(encode_ws_frame(json.dumps(init_msg)))
    await writer.drain()

    # 4. Loop de mensagens do WebSocket
    try:
        while True:
            msg_str = await read_ws_frame(reader)
            if msg_str is None:
                break
            if not msg_str:
                continue

            try:
                data = json.loads(msg_str)
                mtype = data.get("type")
                if mtype == "prompt":
                    await session.send_prompt(data.get("content", ""))
                elif mtype == "interrupt":
                    await session.send_interrupt()
            except json.JSONDecodeError:
                await session.send_prompt(msg_str)
    except Exception as e:
        print(f"[AGY Bridge] Conexão com cliente finalizada: {e}")
    finally:
        session.clients.discard(writer)
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        print(f"[AGY Bridge] Cliente {addr} desconectado")


async def main():
    print("=" * 60)
    print(" Antigravity Android - Termux Bridge v0.0.1 (Zero Deps)")
    print(f" Servidor WebSocket iniciado em: ws://{HOST}:{PORT}")
    print("=" * 60)
    await session.start_process()

    server = await asyncio.start_server(handle_connection, HOST, PORT)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[AGY Bridge] Servidor encerrado.")
