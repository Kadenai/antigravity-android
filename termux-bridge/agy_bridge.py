#!/usr/bin/env python3
"""Frontend Android -> AGY no terminal. Apenas biblioteca padrão, sem pip."""

import asyncio
import argparse
import base64
import codecs
import hashlib
import json
import os
import signal
import shlex
import shutil
import struct
import stat
import sys
import tempfile
import uuid

HOST = "127.0.0.1"
PORT = 8765
AGY_COMMAND = ["agy", "--input-format", "stream-json", "--output-format", "stream-json", "--mode=accept-edits"]
WS_MAGIC = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MESSAGE_SIZE = 4 * 1024 * 1024
PROTOCOL_VERSION = 4


def agy_executable():
    executable = AGY_COMMAND[0]
    if executable == "agy" and not shutil.which(executable):
        installed = os.path.expanduser("~/.local/bin/agy")
        if os.path.isfile(installed) and os.access(installed, os.X_OK):
            return installed
    return executable


def terminal_path(path):
    if not isinstance(path, str) or "\0" in path or path.startswith("content:"):
        raise ValueError("Informe um caminho do terminal, não uma URI Android")
    expanded = os.path.expanduser(path or os.getcwd())
    if not os.path.isabs(expanded):
        raise ValueError("Use um caminho absoluto ou ~/pasta")
    return os.path.realpath(expanded)


def workspace_path(path):
    resolved = terminal_path(path)
    if not os.path.isdir(resolved):
        raise ValueError(f"Pasta inexistente ou inacessível ao terminal: {resolved}")
    try:
        with os.scandir(resolved) as entries:
            next(entries, None)
    except OSError as error:
        raise ValueError(f"Sem acesso à pasta pelo terminal: {error}") from error
    return resolved


def make_handshake_response(key):
    accept = base64.b64encode(hashlib.sha1(key.encode() + WS_MAGIC).digest()).decode()
    return (
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"
    ).encode()


def encode_ws_frame(message, opcode=1):
    data = message.encode("utf-8") if isinstance(message, str) else message
    length = len(data)
    if length <= 125:
        header = struct.pack("!BB", 0x80 | opcode, length)
    elif length <= 65535:
        header = struct.pack("!BBH", 0x80 | opcode, 126, length)
    else:
        header = struct.pack("!BBQ", 0x80 | opcode, 127, length)
    return header + data


async def read_ws_message(reader, writer):
    fragments = bytearray()
    fragmented = False
    while True:
        header = await reader.readexactly(2)
        final, opcode = bool(header[0] & 0x80), header[0] & 0x0F
        masked, length = bool(header[1] & 0x80), header[1] & 0x7F
        if header[0] & 0x70 or not masked:
            raise ValueError("Frame WebSocket inválido")
        if length == 126:
            length = struct.unpack("!H", await reader.readexactly(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", await reader.readexactly(8))[0]
        if length > MAX_MESSAGE_SIZE or (opcode >= 8 and (not final or length > 125)):
            raise ValueError("Frame WebSocket excede o limite")
        mask = await reader.readexactly(4)
        data = await reader.readexactly(length)
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        if opcode == 8:
            writer.write(encode_ws_frame(payload, 8))
            await writer.drain()
            return None
        if opcode == 9:
            writer.write(encode_ws_frame(payload, 10))
            await writer.drain()
            continue
        if opcode == 10:
            continue
        if opcode == 1 and not fragmented:
            fragmented = True
        elif opcode != 0 or not fragmented:
            raise ValueError("Sequência de frames WebSocket inválida")
        fragments.extend(payload)
        if len(fragments) > MAX_MESSAGE_SIZE:
            raise ValueError("Mensagem WebSocket excede o limite")
        if final:
            return fragments.decode("utf-8")


class AntigravitySession:
    def __init__(self):
        self.process = None
        self.monitor_task = None
        self.clients = set()
        self.is_running = False
        self.interrupted = False
        self.has_output = False
        self.conversation_id = None
        self.command_lock = asyncio.Lock()
        self.workspace = os.getcwd()
        self.automatic = False
        self.cli_version = ""
        self.cli_workspace = None
        self.last_status = {"type": "status", "state": "idle", "message": "Conectado à ponte do terminal"}
        self.initialized = None
        self.validation = None
        self.validation_executed = False
        self.stopping = False

    def session_info(self, changed=False):
        return {"type": "session", "workspace": self.workspace, "automatic": self.automatic,
                "conversation_id": self.conversation_id or "", "changed": changed, "cli_version": self.cli_version}

    async def configure(self, path, automatic, reset=False):
        async with self.command_lock:
            target = workspace_path(path)
            changed = target != self.workspace or automatic != self.automatic or reset
            if (self.is_running or self.validation) and changed:
                raise ValueError("Pare o turno antes de trocar workspace, modo ou conversa")
            if not isinstance(automatic, bool) or not isinstance(reset, bool):
                raise ValueError("Configuração de modo inválida")
            # Verifica a versão instalada neste terminal, sem presumir paridade com o APK.
            try:
                help_process = await asyncio.create_subprocess_exec(agy_executable(), "--help", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                try:
                    stdout, stderr = await asyncio.wait_for(help_process.communicate(), 10)
                finally:
                    if help_process.returncode is None:
                        help_process.kill()
                        await help_process.wait()
                help_text = (stdout + stderr).decode("utf-8", errors="replace")
                required = ["--input-format", "--output-format", "--mode"]
                if automatic:
                    required.append("--dangerously-skip-permissions")
                if help_process.returncode or any(flag not in help_text for flag in required):
                    raise ValueError("AGY instalado não oferece as opções necessárias. Atualize o CLI neste terminal.")
                version_process = await asyncio.create_subprocess_exec(agy_executable(), "--version", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                try:
                    version, _ = await asyncio.wait_for(version_process.communicate(), 10)
                finally:
                    if version_process.returncode is None:
                        version_process.kill()
                        await version_process.wait()
                self.cli_version = version.decode("utf-8", errors="replace").strip()
            except (OSError, asyncio.TimeoutError) as error:
                raise ValueError(f"Não foi possível verificar agy: {error}. Confira command -v agy e agy --version no mesmo ambiente da ponte. Instale um CLI compatível com esse ambiente e faça login com agy.") from error
            try:
                with tempfile.TemporaryFile(dir=target) as probe:
                    probe.write(b"workspace-check")
                    probe.seek(0)
                    if probe.read() != b"workspace-check":
                        raise OSError("Falha na leitura após escrita")
            except OSError as error:
                raise ValueError(f"Workspace sem leitura/escrita pelo terminal: {error}. Para armazenamento compartilhado, execute termux-setup-storage.") from error
            if changed:
                await self.stop()
                self.conversation_id = None
            self.workspace, self.automatic = target, automatic
            await self.broadcast({"type": "status", "state": "preparing", "message": "Iniciando AGY e verificando workspace..."})
            if not await self.start_process():
                return
            try:
                error = await asyncio.wait_for(asyncio.shield(self.initialized), 20)
                if error:
                    raise ValueError(error)
                if self.cli_workspace != self.workspace:
                    raise ValueError("O evento init do CLI não confirmou o workspace selecionado")
            except (asyncio.TimeoutError, ValueError) as error:
                await self.stop()
                raise ValueError(f"AGY não ficou pronto: {error}. Confira login e logs do terminal.") from error
            await self.broadcast(self.session_info(changed))
            await self.broadcast({"type": "status", "state": "running" if self.is_running else "idle",
                                  "message": f"AGY {self.cli_version} · {target}"})

    async def list_workspaces(self, path, writer):
        root = workspace_path(path)
        directories = []
        with os.scandir(root) as entries:
            for entry in entries:
                if entry.is_dir():
                    directories.append(entry.path)
        writer.write(encode_ws_frame(json.dumps({"type": "workspaces", "root": root,
            "paths": sorted(directories), "parent": os.path.dirname(root)}, ensure_ascii=False)))
        await writer.drain()

    async def list_files(self, path, writer):
        target = terminal_path(self.workspace if path == "" else path)
        if os.path.isdir(target):
            root = workspace_path(target)
            with os.scandir(root) as entries:
                paths, files = [], []
                for entry in entries:
                    if entry.is_dir():
                        paths.append(entry.path)
                    elif entry.is_file():
                        files.append(entry.path)
            data = {"type": "files", "root": root, "parent": os.path.dirname(root),
                    "paths": sorted(paths), "files": sorted(files)}
        else:
            if not os.path.isfile(target):
                raise ValueError(f"Arquivo inexistente ou inacessível ao terminal: {target}")
            descriptor = os.open(target, os.O_RDONLY | getattr(os, "O_NONBLOCK", 0))
            try:
                if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                    raise ValueError("Selecione um arquivo regular")
            finally:
                os.close(descriptor)
            data = {"type": "files", "selected": target}
        writer.write(encode_ws_frame(json.dumps(data, ensure_ascii=False)))
        await writer.drain()

    async def validate_workspace(self):
        if self.is_running or self.validation:
            raise ValueError("Aguarde o turno atual antes de validar o workspace")
        if not self.process or not self.initialized or not self.initialized.done():
            raise ValueError("Conecte uma sessão AGY antes de validar o workspace")
        folder = tempfile.TemporaryDirectory(prefix=".agy-access-", dir=self.workspace)
        token = uuid.uuid4().hex
        input_path = os.path.join(folder.name, "input.txt")
        output_path = os.path.join(folder.name, "output.txt")
        with open(input_path, "w", encoding="utf-8") as source:
            source.write(token)
        code = f"from pathlib import Path; p=Path({input_path!r}); t=p.read_text(); assert t=={token!r}; Path({output_path!r}).write_text(t)"
        command = shlex.quote(sys.executable) + " -c " + shlex.quote(code)
        self.validation = (folder, output_path, token, command)
        self.validation_executed = False
        await self.send_prompt("Valide leitura, escrita e execução neste workspace. Execute exatamente este comando usando run_command e informe o resultado, sem outras alterações:\n" + command)

    async def finish_validation(self):
        if not self.validation:
            return
        folder, output_path, token, _ = self.validation
        self.validation = None
        try:
            with open(output_path, encoding="utf-8") as receipt:
                valid = receipt.read() == token and self.validation_executed
        except OSError:
            valid = False
        finally:
            try:
                folder.cleanup()
            except OSError as error:
                await self.broadcast({"type": "output", "stream": "stderr", "chunk": f"\nNão foi possível remover a pasta temporária {folder.name}: {error}\n"})
        await self.broadcast({"type": "output", "stream": "stdout", "chunk": "\n✅ AGY confirmou leitura, escrita e execução no workspace.\n" if valid else "\n⚠️ AGY não confirmou o acesso. Confira os logs e as permissões de comandos no CLI; ative o modo automático explicitamente ou configure permissions.allow.\n"})

    async def start_process(self):
        if self.process and self.process.returncode is None:
            return True
        if self.monitor_task:
            await self.monitor_task
        command = [agy_executable(), *AGY_COMMAND[1:]] + (["--dangerously-skip-permissions"] if self.automatic else []) + (["--conversation", self.conversation_id] if self.conversation_id else [])
        try:
            process = await asyncio.create_subprocess_exec(
                *command, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE, limit=MAX_MESSAGE_SIZE,
                cwd=self.workspace,
                start_new_session=(os.name == "posix")
            )
        except OSError as error:
            await self.broadcast({"type": "status", "state": "error", "message":
                f"Não foi possível iniciar agy: {error}. Confira command -v agy e agy --version no mesmo ambiente da ponte. Instale um CLI compatível com esse ambiente e faça login executando agy."})
            return False
        self.process = process
        self.stopping = False
        self.initialized = asyncio.get_running_loop().create_future()
        self.cli_workspace = None
        self.interrupted = False
        readers = [asyncio.create_task(self._read_stdout(process)), asyncio.create_task(self._read_stderr(process.stderr))]
        self.monitor_task = asyncio.create_task(self._monitor(process, readers))
        return True

    async def _read_stdout(self, process):
        try:
            while line := await process.stdout.readline():
                event = json.loads(line)
                if not isinstance(event, dict):
                    raise ValueError("Evento JSON inválido")
                if event.get("event") == "init":
                    self.conversation_id = event.get("conversation_id")
                    config = event.get("init", {})
                    self.cli_workspace = os.path.realpath(config["cwd"]) if config.get("cwd") else None
                    error = None
                    if self.cli_workspace and self.cli_workspace != self.workspace:
                        error = "O CLI iniciou em outro workspace"
                    if self.automatic and config.get("permission_mode") != "always-proceed":
                        error = "O CLI não confirmou o modo automático"
                    if not self.initialized.done():
                        self.initialized.set_result(error)
                    if error:
                        await self.broadcast({"type": "status", "state": "error", "message": error})
                        process.terminate()
                elif event.get("event") == "step_update":
                    step = event.get("step_update", {})
                    tool = step.get("tool_info", {})
                    if (self.validation and step.get("state") == "DONE" and step.get("tool_name") == "run_command"
                            and tool.get("parameters", {}).get("CommandLine") == self.validation[3] and not tool.get("error")):
                        self.validation_executed = True
                    if step.get("step_type") == "agent_response" and step.get("text_delta"):
                        self.has_output = True
                        await self.broadcast({"type": "output", "stream": "stdout", "chunk": step["text_delta"]})
                elif event.get("event") == "result":
                    result = event.get("result", {})
                    self.conversation_id = result.get("conversation_id") or self.conversation_id
                    if not self.has_output and result.get("response"):
                        await self.broadcast({"type": "output", "stream": "stdout", "chunk": result["response"]})
                    error = result.get("error")
                    failed = error or result.get("status") in ("ERROR", "INVALID", "WAITING")
                    self.is_running = False
                    await self.finish_validation()
                    await self.broadcast({"type": "status", "state": "error" if failed else "idle",
                                          "message": str(error or ("Falha no AGY" if failed else "Pronto para próxima mensagem"))})
                    await self.broadcast({"type": "turn_complete"})
        except (ValueError, TypeError, AttributeError) as error:
            await self.broadcast({"type": "status", "state": "error", "message":
                f"Saída do CLI incompatível com stream-json: {error}. Atualize o AGY neste terminal."})
            if process.returncode is None:
                process.terminate()

    async def _read_stderr(self, stream):
        decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
        while chunk := await stream.read(4096):
            text = decoder.decode(chunk)
            if text:
                await self.broadcast({"type": "output", "stream": "stderr", "chunk": text})
        tail = decoder.decode(b"", final=True)
        if tail:
            await self.broadcast({"type": "output", "stream": "stderr", "chunk": tail})

    async def _monitor(self, process, readers):
        code = await process.wait()
        await asyncio.gather(*readers)
        if self.process is process:
            self.process = None
        if self.initialized and not self.initialized.done():
            self.initialized.set_result(f"Processo encerrou (código {code}) antes do evento init")
        if self.is_running:
            self.is_running = False
            await self.finish_validation()
            await self.broadcast({"type": "status", "state": "interrupted" if self.interrupted else "error",
                                  "message": "Execução cancelada" if self.interrupted else f"AGY encerrou antes de concluir (código {code}). Confira os logs do terminal e o login do CLI."})
            await self.broadcast({"type": "turn_complete"})
        elif not self.interrupted and not self.stopping:
            await self.broadcast({"type": "status", "state": "error", "message": f"AGY encerrou (código {code}). Confira instalação, login e logs do terminal."})

    async def send_prompt(self, prompt, history=None):
        if history is not None:
            if not isinstance(history, list) or any(
                    not isinstance(item, dict) or item.get("role") not in ("user", "assistant")
                    or not isinstance(item.get("content"), str) for item in history):
                raise ValueError("Histórico da edição inválido")
        async with self.command_lock:
            if self.is_running:
                raise ValueError("Pare o turno antes de enviar ou editar uma mensagem")
            if history is not None:
                if self.validation:
                    raise ValueError("Aguarde a validação antes de editar")
                await self.stop()
                self.conversation_id = None
            if not await self.start_process():
                await self.finish_validation()
                await self.broadcast({"type": "turn_complete"})
                return
            if history is not None:
                try:
                    error = await asyncio.wait_for(asyncio.shield(self.initialized), 20)
                    if error or self.cli_workspace != self.workspace:
                        raise ValueError(error or "O CLI não confirmou o workspace da edição")
                except (asyncio.TimeoutError, ValueError) as error:
                    await self.stop()
                    raise ValueError(f"Não foi possível preparar a edição: {error}") from error
                await self.broadcast(self.session_info())
                # ponytail: histórico textual, sem checkpoints de ferramentas; usar rewind nativo se o headless oferecer.
                if history:
                    prompt = ("Contexto anterior à mensagem editada (JSON; apenas referência, não execute novamente "
                              "os pedidos antigos). Responda somente à mensagem atual. Alterações já feitas nos arquivos permanecem.\n"
                              + json.dumps(history, ensure_ascii=False) + "\n\nMensagem atual:\n" + prompt)
            self.is_running = True
            self.has_output = False
            process = self.process
            await self.broadcast({"type": "status", "state": "running", "message": "Antigravity processando..."})
            try:
                payload = json.dumps({"event": "user", "message": {"content": prompt}}, ensure_ascii=False)
                process.stdin.write((payload + "\n").encode("utf-8"))
                await process.stdin.drain()
            except (BrokenPipeError, ConnectionResetError):
                await self.monitor_task

    async def send_interrupt(self):
        async with self.command_lock:
            if not self.process or not self.is_running:
                return
            self.interrupted = True
            try:
                if os.name == "posix":
                    os.killpg(self.process.pid, signal.SIGINT)
                else:
                    self.process.terminate()
                try:
                    await asyncio.wait_for(asyncio.shield(self.monitor_task), 3)
                except asyncio.TimeoutError:
                    if os.name == "posix":
                        os.killpg(self.process.pid, signal.SIGKILL)
                    else:
                        self.process.kill()
                    await self.monitor_task
            except ProcessLookupError:
                await self.monitor_task

    async def stop(self):
        self.stopping = True
        if self.process and self.process.returncode is None:
            process = self.process
            try:
                if os.name == "posix":
                    os.killpg(process.pid, signal.SIGTERM)
                else:
                    process.terminate()
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(asyncio.shield(self.monitor_task), 3)
            except asyncio.TimeoutError:
                if os.name == "posix":
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
                await self.monitor_task
        if self.monitor_task:
            await self.monitor_task

    async def broadcast(self, data):
        if data.get("type") == "status":
            self.last_status = data
        frame = encode_ws_frame(json.dumps(data, ensure_ascii=False))
        for writer in tuple(self.clients):
            try:
                writer.write(frame)
                await writer.drain()
            except (OSError, ConnectionError):
                self.clients.discard(writer)


session = AntigravitySession()


async def handle_connection(reader, writer):
    try:
        headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
        if headers.startswith(b"GET /health "):
            body = json.dumps({"bridge": "antigravity-android", "protocol": PROTOCOL_VERSION}).encode()
            writer.write(f"HTTP/1.1 200 OK\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body)
            await writer.drain()
            return
        fields = dict(line.split(":", 1) for line in headers.decode("ascii").split("\r\n")[1:] if ":" in line)
        fields = {key.lower(): value.strip() for key, value in fields.items()}
        key = fields.get("sec-websocket-key", "")
        if (len(base64.b64decode(key, validate=True)) != 16 or fields.get("sec-websocket-version") != "13"
                or fields.get("upgrade", "").lower() != "websocket" or "origin" in fields):
            writer.write(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            await writer.drain()
            return
        writer.write(make_handshake_response(key))
        writer.write(encode_ws_frame(json.dumps({"type": "hello", "protocol": PROTOCOL_VERSION})))
        writer.write(encode_ws_frame(json.dumps(session.last_status)))
        await writer.drain()
        session.clients.add(writer)
        while (message := await read_ws_message(reader, writer)) is not None:
            try:
                data = json.loads(message)
                if not isinstance(data, dict):
                    raise ValueError("Mensagem deve ser um objeto JSON")
                if data.get("type") == "prompt" and isinstance(data.get("content"), str) and data["content"].strip():
                    await session.send_prompt(data["content"], data.get("history"))
                elif data.get("type") == "interrupt":
                    await session.send_interrupt()
                elif data.get("type") == "configure":
                    await session.configure(data.get("workspace", ""), data.get("automatic", False), data.get("new_conversation", False))
                elif data.get("type") == "workspaces":
                    await session.list_workspaces(data.get("root", "~"), writer)
                elif data.get("type") == "files":
                    await session.list_files(data.get("root", ""), writer)
                elif data.get("type") == "validate_workspace":
                    await session.validate_workspace()
                else:
                    raise ValueError("Tipo ou conteúdo de mensagem inválido")
            except (ValueError, OSError) as error:
                writer.write(encode_ws_frame(json.dumps({"type": "error", "message": str(error)})))
                await writer.drain()
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError, OSError, ValueError):
        pass
    finally:
        session.clients.discard(writer)
        writer.close()
        try:
            await writer.wait_closed()
        except OSError:
            pass


async def main(reuse=False):
    print(f"Antigravity Android - ponte local: ws://{HOST}:{PORT}", flush=True)
    print(f"Workspace: {os.getcwd()}", flush=True)
    print("Faça login com agy neste terminal antes de enviar mensagens.", flush=True)
    try:
        server = await asyncio.start_server(handle_connection, HOST, PORT)
    except OSError:
        if not reuse:
            raise
        reader, writer = await asyncio.wait_for(asyncio.open_connection(HOST, PORT), 3)
        try:
            writer.write(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            await writer.drain()
            response = await asyncio.wait_for(reader.read(), 3)
            health = json.loads(response.split(b"\r\n\r\n", 1)[1])
            if health != {"bridge": "antigravity-android", "protocol": PROTOCOL_VERSION}:
                raise ValueError("Porta ocupada por outro serviço ou versão antiga da ponte. Pare a ponte antiga no terminal.")
            print("Ponte existente reutilizada.", flush=True)
            return
        finally:
            writer.close()
            await writer.wait_closed()
    try:
        async with server:
            await server.serve_forever()
    finally:
        await session.stop()


if __name__ == "__main__":
    try:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--reuse", action="store_true", help="Reutilizar a ponte existente sem duplicar processos")
        asyncio.run(main(parser.parse_args().reuse))
    except KeyboardInterrupt:
        print("\nPonte encerrada.")
