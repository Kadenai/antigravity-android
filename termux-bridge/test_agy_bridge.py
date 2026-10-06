import asyncio
import json
import os
import tempfile
import struct
import sys
import unittest
from unittest.mock import patch

import agy_bridge as bridge


class MemoryWriter:
    def __init__(self):
        self.frames = []

    def write(self, frame):
        self.frames.append(frame)

    async def drain(self):
        pass


def decode_frame(frame):
    length = frame[1] & 127
    offset = 2
    if length == 126:
        length, offset = struct.unpack("!H", frame[2:4])[0], 4
    elif length == 127:
        length, offset = struct.unpack("!Q", frame[2:10])[0], 10
    return frame[0] & 15, frame[offset:offset + length]


def masked_frame(payload, opcode=1, final=True):
    mask = b"test"
    return bytes([(128 if final else 0) | opcode, 128 | len(payload)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))


class BridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_file_picker_lists_terminal_paths_and_checks_read_access_without_copying(self):
        session, writer = bridge.AntigravitySession(), MemoryWriter()
        with tempfile.TemporaryDirectory() as root:
            folder = os.path.join(root, "pasta")
            os.mkdir(folder)
            file = os.path.join(root, "ação com espaço.txt")
            with open(file, "w", encoding="utf-8") as source:
                source.write("conteúdo")
            await session.list_files(root, writer)
            listing = json.loads(decode_frame(writer.frames[-1])[1])
            self.assertEqual([folder], listing["paths"])
            self.assertEqual([file], listing["files"])
            await session.list_files(file, writer)
            self.assertEqual(os.path.realpath(file), json.loads(decode_frame(writer.frames[-1])[1])["selected"])
            self.assertEqual(["ação com espaço.txt", "pasta"], sorted(os.listdir(root)))
            for invalid in ["content://documents/1", "relative.txt", os.path.join(root, "missing"), 0, None, 123]:
                with self.assertRaises(ValueError):
                    await session.list_files(invalid, writer)
            with patch.object(os, "open", side_effect=PermissionError("sem acesso")):
                with self.assertRaises(PermissionError):
                    await session.list_files(file, writer)

    async def test_edit_restarts_cli_with_only_retained_text_and_one_current_turn(self):
        script = '''
import json, os, sys, uuid
print(json.dumps({"event":"init", "conversation_id":str(uuid.uuid4()), "init":{"cwd":os.getcwd()}}), flush=True)
turns = 0
for line in sys.stdin:
    turns += 1
    prompt = json.loads(line)["message"]["content"]
    response = json.dumps({"prompt":prompt, "turns":turns, "argv":sys.argv})
    print(json.dumps({"event":"result", "result":{"status":"SUCCESS", "response":response}}), flush=True)
'''
        session, writer = bridge.AntigravitySession(), MemoryWriter()
        session.clients.add(writer)
        async def ask(prompt, history=None):
            writer.frames.clear()
            await session.send_prompt(prompt, history)
            async with asyncio.timeout(5):
                while session.is_running:
                    await asyncio.sleep(.01)
            messages = [json.loads(decode_frame(frame)[1]) for frame in writer.frames]
            response = json.loads("".join(m["chunk"] for m in messages if m["type"] == "output"))
            self.assertEqual("turn_complete", messages[-1]["type"])
            return response
        with patch.object(bridge, "AGY_COMMAND", [sys.executable, "-u", "-c", script]):
            try:
                await ask("mensagem original")
                await ask("mensagem posterior descartada")
                previous_process, previous_conversation = session.process, session.conversation_id
                with self.assertRaisesRegex(ValueError, "Histórico"):
                    await session.send_prompt("editado", [{"role":"system", "content":"invalid"}])
                self.assertIs(previous_process, session.process)
                edited = "    mensagem editada\n\t😀\n@/storage/emulated/0/Meu projeto/ação.txt"
                retained = [{"role":"user", "content":"antes"}, {"role":"assistant", "content":"resposta anterior"}]
                response = await ask(edited, retained)
                self.assertIsNot(previous_process, session.process)
                self.assertNotEqual(previous_conversation, session.conversation_id)
                self.assertNotIn("--conversation", response["argv"])
                self.assertEqual(1, response["turns"])
                self.assertTrue(response["prompt"].endswith(edited))
                self.assertIn(json.dumps(retained, ensure_ascii=False), response["prompt"])
                self.assertNotIn("mensagem original", response["prompt"])
                self.assertNotIn("mensagem posterior descartada", response["prompt"])
                self.assertEqual(2, (await ask("continuação"))["turns"])
                self.assertEqual("primeira editada", (await ask("primeira editada", []))["prompt"])
                session.is_running = True
                with self.assertRaisesRegex(ValueError, "Pare o turno"):
                    await session.send_prompt("outro", [])
                session.is_running = False
            finally:
                await session.stop()

    async def test_configure_reuse_switch_mode_and_validate_real_child_access(self):
        script = '''
import json, os, shlex, subprocess, sys, uuid, time
if "--help" in sys.argv:
    print("--input-format --output-format --mode --dangerously-skip-permissions")
    sys.exit(0)
if "--version" in sys.argv:
    print("test-1.2.16")
    sys.exit(0)
print(json.dumps({"event":"init", "conversation_id":str(uuid.uuid4()), "init":{"cwd":os.getcwd(),
    "permission_mode":"always-proceed" if "--dangerously-skip-permissions" in sys.argv else "request-review"}}), flush=True)
for line in sys.stdin:
    prompt = json.loads(line)["message"]["content"]
    if prompt.startswith("Valide") and "--dangerously-skip-permissions" in sys.argv:
        command = prompt.rsplit("\\n", 1)[-1]
        subprocess.run(shlex.split(command), check=True)
        print(json.dumps({"event":"step_update", "step_update":{"step_type":"tool", "state":"DONE", "tool_name":"run_command",
            "tool_info":{"parameters":{"CommandLine":command}}}}), flush=True)
    if prompt == "wait":
        time.sleep(60)
    print(json.dumps({"event":"result", "result":{"status":"SUCCESS", "response":"ok"}}), flush=True)
'''
        session, writer = bridge.AntigravitySession(), MemoryWriter()
        session.clients.add(writer)
        original_exec = asyncio.create_subprocess_exec
        with tempfile.TemporaryDirectory() as root:
            stub = os.path.join(root, "fake_agy.py")
            with open(stub, "w", encoding="utf-8") as source:
                source.write(script)
            first, second = os.path.join(root, "project α"), os.path.join(root, "shared")
            os.mkdir(first)
            os.mkdir(second)
            async def spawn(*args, **kwargs):
                if args[0] == "agy":
                    args = (sys.executable, "-u", stub, *args[1:])
                return await original_exec(*args, **kwargs)
            with patch.object(asyncio, "create_subprocess_exec", side_effect=spawn):
                try:
                    await session.configure(first, False)
                    self.assertEqual("test-1.2.16", session.cli_version)
                    process, conversation = session.process, session.conversation_id
                    await session.configure(first, False)
                    self.assertIs(process, session.process)
                    self.assertEqual(conversation, session.conversation_id)
                    await session.configure(second, True)
                    self.assertIsNot(process, session.process)
                    self.assertNotEqual(conversation, session.conversation_id)
                    self.assertTrue(session.automatic)
                    writer.frames.clear()
                    await session.validate_workspace()
                    async with asyncio.timeout(5):
                        while session.validation:
                            await asyncio.sleep(.01)
                    messages = [json.loads(decode_frame(frame)[1]) for frame in writer.frames]
                    self.assertTrue(any("AGY confirmou" in m.get("chunk", "") for m in messages))
                    self.assertFalse(any(name.startswith(".agy-access-") for name in os.listdir(second)))
                    conversation = session.conversation_id
                    await session.configure(second, True, reset=True)
                    self.assertNotEqual(conversation, session.conversation_id)
                    await session.send_prompt("wait")
                    with self.assertRaisesRegex(ValueError, "Pare o turno"):
                        await session.configure(first, False)
                    self.assertEqual(os.path.realpath(second), session.workspace)
                    await session.send_interrupt()
                    writer.frames.clear()
                    await session.list_workspaces(root, writer)
                    listing = json.loads(decode_frame(writer.frames[0])[1])
                    self.assertEqual(sorted([first, second]), listing["paths"])
                    with self.assertRaises(ValueError):
                        await session.configure("content://documents/tree/primary", True)
                    with self.assertRaises(ValueError):
                        await session.configure(os.path.join(root, "missing"), True)
                finally:
                    await session.stop()

    async def test_automatic_mode_requires_supported_flag_and_confirmed_init(self):
        session = bridge.AntigravitySession()
        original_exec = asyncio.create_subprocess_exec
        async def old_cli(*args, **kwargs):
            if args[0] == "agy":
                args = (sys.executable, "-c", "print('--input-format --output-format --mode')")
            return await original_exec(*args, **kwargs)
        with patch.object(asyncio, "create_subprocess_exec", side_effect=old_cli):
            with self.assertRaisesRegex(ValueError, "opções necessárias"):
                await session.configure(os.getcwd(), True)
        self.assertFalse(session.automatic)
        self.assertIsNone(session.process)
        async def wrong_init(*args, **kwargs):
            if args[0] == "agy":
                if "--help" in args:
                    code = "print('--input-format --output-format --mode --dangerously-skip-permissions')"
                elif "--version" in args:
                    code = "print('test')"
                else:
                    code = "import json, os, time; print(json.dumps({'event':'init','init':{'permission_mode':'request-review'}}), flush=True); time.sleep(60)"
                args = (sys.executable, "-c", code)
            return await original_exec(*args, **kwargs)
        with patch.object(asyncio, "create_subprocess_exec", side_effect=wrong_init):
            with self.assertRaisesRegex(ValueError, "não confirmou o modo automático"):
                await session.configure(os.getcwd(), True)
            with self.assertRaisesRegex(ValueError, "não confirmou o workspace selecionado"):
                await session.configure(os.getcwd(), False)
        self.assertIsNone(session.process)

    async def test_loopback_listener_health_reuses_without_starting_another_cli(self):
        session = bridge.AntigravitySession()
        with patch.object(bridge, "session", session):
            server = await asyncio.start_server(bridge.handle_connection, bridge.HOST, 0)
            port = server.sockets[0].getsockname()[1]
            async with server:
                with patch.object(bridge, "PORT", port), patch("builtins.print"):
                    await bridge.main(reuse=True)
                self.assertIsNone(session.process)
                reader, writer = await asyncio.open_connection(bridge.HOST, port)
                try:
                    writer.write(b"GET / HTTP/1.1\r\nUpgrade: websocket\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nOrigin: https://example.com\r\n\r\n")
                    await writer.drain()
                    self.assertIn(b"400 Bad Request", await reader.read())
                finally:
                    writer.close()
                    await writer.wait_closed()

    async def test_reuse_rejects_running_old_bridge(self):
        async def old_health(reader, writer):
            await reader.readuntil(b"\r\n\r\n")
            body = json.dumps({"bridge": "antigravity-android", "protocol": 3}).encode()
            writer.write(f"HTTP/1.1 200 OK\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body)
            await writer.drain()
            writer.close()
            await writer.wait_closed()
        server = await asyncio.start_server(old_health, bridge.HOST, 0)
        async with server:
            with patch.object(bridge, "PORT", server.sockets[0].getsockname()[1]), patch("builtins.print"):
                with self.assertRaisesRegex(ValueError, "versão antiga"):
                    await bridge.main(reuse=True)

    async def test_startup_exit_and_stderr_are_visible_before_any_prompt(self):
        writer, session = MemoryWriter(), bridge.AntigravitySession()
        session.clients.add(writer)
        with patch.object(bridge, "AGY_COMMAND", [sys.executable, "-c", "import sys; print('login necessário', file=sys.stderr); sys.exit(1)"]):
            await session.start_process()
            await session.monitor_task
        messages = [json.loads(decode_frame(frame)[1]) for frame in writer.frames]
        self.assertTrue(any("login necessário" in m.get("chunk", "") for m in messages))
        self.assertEqual("error", messages[-1]["state"])
        self.assertIn("antes do evento init", await session.initialized)

    async def test_ping_and_fragmented_utf8_prompt(self):
        reader, writer = asyncio.StreamReader(), MemoryWriter()
        text = 'código'.encode()
        reader.feed_data(masked_frame(text[:2], final=False) + masked_frame(b"heartbeat", 9) + masked_frame(text[2:], 0))
        self.assertEqual("código", await bridge.read_ws_message(reader, writer))
        self.assertEqual((10, b"heartbeat"), decode_frame(writer.frames[0]))

    async def test_continuous_session_preserves_prompts_and_completes_after_output(self):
        # Processo real, protocolo documentado do CLI; sem autenticação/rede externas.
        script = '''
import json, sys, time
print(json.dumps({"event":"init", "conversation_id":"test-conversation"}), flush=True)
for line in sys.stdin:
    prompt = json.loads(line)["message"]["content"]
    if prompt == "exit":
        print(json.dumps({"event":"step_update","step_update":{"step_type":"agent_response","text_delta":"último trecho"}}), flush=True)
        sys.exit(1)
    if prompt == "wait":
        time.sleep(60)
    if prompt == "error":
        print(json.dumps({"event":"result","result":{"status":"ERROR","error":"login necessário"}}), flush=True)
        continue
    for char in prompt:
        print(json.dumps({"event":"step_update","step_update":{"step_type":"agent_response","text_delta":char}}), flush=True)
    print(json.dumps({"event":"result","result":{"status":"SUCCESS","response":prompt}}), flush=True)
'''
        writer = MemoryWriter()
        session = bridge.AntigravitySession()
        session.clients.add(writer)
        def messages():
            return [json.loads(decode_frame(frame)[1]) for frame in writer.frames]
        async def wait_complete():
            async with asyncio.timeout(5):
                while not any(m["type"] == "turn_complete" for m in messages()):
                    await asyncio.sleep(.01)
        with patch.object(bridge, "AGY_COMMAND", [sys.executable, "-u", "-c", script]):
            try:
                for prompt in ["    código\n\t😀\n", "segunda resposta"]:
                    writer.frames.clear()
                    await session.send_prompt(prompt)
                    process = session.process
                    await wait_complete()
                    self.assertIs(process, session.process)
                    self.assertIsNone(process.returncode)
                    self.assertEqual(prompt, "".join(m["chunk"] for m in messages() if m["type"] == "output"))
                    self.assertEqual("turn_complete", messages()[-1]["type"])
                writer.frames.clear()
                await session.send_prompt("error")
                await wait_complete()
                self.assertTrue(any(m.get("state") == "error" and m["message"] == "login necessário" for m in messages()))
                writer.frames.clear()
                await session.send_prompt("exit")
                await wait_complete()
                self.assertEqual("último trecho", "".join(m["chunk"] for m in messages() if m["type"] == "output"))
                self.assertEqual("turn_complete", messages()[-1]["type"])
                writer.frames.clear()
                await session.send_prompt("wait")
                await session.send_interrupt()
                self.assertFalse(session.is_running)
                self.assertEqual("turn_complete", messages()[-1]["type"])
                writer.frames.clear()
                await session.send_prompt("depois do cancelamento")
                await wait_complete()
                self.assertEqual("depois do cancelamento", "".join(m["chunk"] for m in messages() if m["type"] == "output"))
            finally:
                await session.stop()

    async def test_local_bin_fallback_is_used_for_help_version_and_session(self):
        script = '''
import json, os, sys
if "--help" in sys.argv:
    print("--input-format --output-format --mode")
elif "--version" in sys.argv:
    print("test-local-bin")
else:
    print(json.dumps({"event":"init", "init":{"cwd":os.getcwd()}}), flush=True)
    for line in sys.stdin:
        print(json.dumps({"event":"result", "result":{"status":"SUCCESS", "response":"ok"}}), flush=True)
'''
        session = bridge.AntigravitySession()
        original_exec = asyncio.create_subprocess_exec
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HOME": home, "USERPROFILE": home}), patch.object(bridge.shutil, "which", return_value=None):
            installed = os.path.expanduser("~/.local/bin/agy")
            self.assertEqual("agy", bridge.agy_executable())
            os.makedirs(os.path.dirname(installed))
            with open(installed, "w", encoding="utf-8") as source:
                source.write(script)
            os.chmod(installed, 0o700)
            self.assertEqual(installed, bridge.agy_executable())
            with patch.object(bridge.shutil, "which", return_value="/preferred/agy"):
                self.assertEqual("agy", bridge.agy_executable())
            calls = []
            async def spawn(*args, **kwargs):
                calls.append(args)
                self.assertEqual(installed, args[0])
                return await original_exec(sys.executable, "-u", *args, **kwargs)
            with patch.object(asyncio, "create_subprocess_exec", side_effect=spawn):
                try:
                    await session.configure(home, False)
                    self.assertEqual("test-local-bin", session.cli_version)
                    self.assertEqual(["--help", "--version", "--input-format"], [args[1] for args in calls])
                    await session.send_prompt("hello")
                    async with asyncio.timeout(5):
                        while session.is_running:
                            await asyncio.sleep(.01)
                finally:
                    await session.stop()

    async def test_missing_cli_is_an_actionable_error_and_finishes_turn(self):
        session, writer = bridge.AntigravitySession(), MemoryWriter()
        session.clients.add(writer)
        with patch.object(bridge, "AGY_COMMAND", ["agy-command-that-does-not-exist"]):
            await session.send_prompt("hello")
        messages = [json.loads(decode_frame(frame)[1]) for frame in writer.frames]
        self.assertEqual("error", messages[0]["state"])
        self.assertIn("faça login", messages[0]["message"])
        self.assertIn("agy --version", messages[0]["message"])
        self.assertEqual("turn_complete", messages[-1]["type"])


if __name__ == "__main__":
    unittest.main()
