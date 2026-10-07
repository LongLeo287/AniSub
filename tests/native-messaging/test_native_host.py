import importlib.util
import io
import json
import struct
import sys
import subprocess
import tempfile
import shutil
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / "apps/desktop/native-messaging/native_host.py"
SPEC = importlib.util.spec_from_file_location("anisub_native", SOURCE)
host = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(host)
ORIGIN = "chrome-extension://" + "a" * 32 + "/"


def snapshot(**overrides):
    value = dict(type="snapshot", session="unit-session", revision=0, sequence=1, positionMs=0,
                 playing=True, speed=1, language="vi", cues=[dict(startMs=0, endMs=1000, text="Synthetic test")])
    value.update(overrides); return value


class HostTests(unittest.TestCase):
    def test_utf8_round_trip(self):
        stream = io.BytesIO(); host.write_frame(stream, {"text": "Tiếng Việt"}); stream.seek(0)
        self.assertEqual(host.read_frame(stream), {"text": "Tiếng Việt"})

    def test_frame_little_endian(self):
        stream = io.BytesIO(); host.write_frame(stream, {"ok": True})
        data = stream.getvalue(); self.assertEqual(struct.unpack("<I", data[:4])[0], len(data) - 4)

    def test_empty_eof(self): self.assertIsNone(host.read_frame(io.BytesIO()))

    def test_short_prefix_rejected(self):
        with self.assertRaises(host.ProtocolError): host.read_frame(io.BytesIO(b"\x01\x00"))

    def test_oversize_before_body(self):
        with self.assertRaises(host.ProtocolError): host.read_frame(io.BytesIO(struct.pack("<I", 65537)))

    def test_truncated_body(self):
        with self.assertRaises(host.ProtocolError): host.read_frame(io.BytesIO(struct.pack("<I", 5) + b"{}"))

    def test_invalid_utf8(self):
        with self.assertRaises(host.ProtocolError): host.read_frame(io.BytesIO(struct.pack("<I", 1) + b"\xff"))

    def test_nan_rejected(self):
        data = b'{"x":NaN}'
        with self.assertRaises(host.ProtocolError): host.read_frame(io.BytesIO(struct.pack("<I", len(data)) + data))

    def test_origin_exact(self):
        self.assertTrue(host.validate_origin(ORIGIN, [ORIGIN])); self.assertTrue(host.validate_origin(ORIGIN[:-1], [ORIGIN]))
        for origin in (ORIGIN + "other", "https://example.org/", "chrome-extension://" + "b" * 32 + "/", "chrome-extension://" + "z" * 32 + "/"):
            self.assertFalse(host.validate_origin(origin, [ORIGIN]))

    def test_unknown_fields_stripped(self):
        self.assertNotIn("url", host.validate_message(snapshot(url="https://invalid.test")))

    def test_unsupported_languages(self):
        with self.assertRaises(host.ProtocolError): host.validate_message(snapshot(language="ja"))

    def test_cue_bounds(self):
        for cues in ([dict(startMs=0, endMs=1, text="x" * 513)], [dict(startMs=1, endMs=0, text="x")], snapshot()["cues"] * 33):
            with self.assertRaises(host.ProtocolError): host.validate_message(snapshot(cues=cues))

    def test_utf16_bounds(self):
        with self.assertRaises(host.ProtocolError): host.validate_message(snapshot(cues=[dict(startMs=0, endMs=1, text="😀" * 257)]))

    def test_lone_surrogate_rejected_as_typed_error(self):
        with self.assertRaises(host.ProtocolError): host.validate_message(snapshot(cues=[dict(startMs=0, endMs=1, text="\ud800")]))

    def test_bool_is_not_revision(self):
        with self.assertRaises(host.ProtocolError): host.validate_message(snapshot(revision=True))

    def test_untrusted_origin_never_contacts_pipe(self):
        output = io.BytesIO(); calls = []
        self.assertEqual(host.run(io.BytesIO(), output, "https://invalid.test", [ORIGIN], lambda m: calls.append(m)), 1)
        self.assertFalse(calls); output.seek(0); self.assertEqual(host.read_frame(output)["error"], "UNAUTHORIZED")

    def test_order_guards_and_eof_close(self):
        stream, output, calls = io.BytesIO(), io.BytesIO(), []
        for message in (snapshot(), snapshot(sequence=1), snapshot(sequence=2, revision=1), snapshot(sequence=3, revision=0)):
            host.write_frame(stream, message)
        stream.seek(0)
        host.run(stream, output, ORIGIN, [ORIGIN], lambda m: calls.append(m) or {"ok": True, "speaking": False})
        self.assertEqual([x["type"] for x in calls], ["snapshot", "snapshot", "close"])
        self.assertEqual(calls[-1]["sequence"], 3)
        output.seek(0); replies = [host.read_frame(output) for _ in range(4)]
        self.assertEqual(replies[1]["error"], "STALE_MESSAGE"); self.assertEqual(replies[3]["error"], "STALE_MESSAGE")

    def test_close_reopens_fresh_session(self):
        stream, output, calls = io.BytesIO(), io.BytesIO(), []
        for message in (snapshot(), dict(type="close", session="unit-session", revision=1, sequence=2), snapshot(session="new-session")):
            host.write_frame(stream, message)
        stream.seek(0); host.run(stream, output, ORIGIN, [ORIGIN], lambda m: calls.append(m) or {"ok": True})
        self.assertEqual(calls[2]["session"], "new-session")

    @unittest.skipUnless(sys.platform == 'win32', 'Windows .NET Framework binary relay')
    def test_compiled_native_launcher_binary_relay(self):
        import os
        compiler = Path(os.environ['WINDIR']) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
        with tempfile.TemporaryDirectory(prefix='anisub-native-test-') as folder:
            directory = Path(folder); registration = directory / 'registration'; registration.mkdir()
            exe = registration / 'AniSubNativeHost.exe'
            source = SOURCE.parent / 'NativeLauncher.cs'
            compiled = subprocess.run([str(compiler), '/nologo', '/target:exe', '/out:' + str(exe), str(source)], capture_output=True, timeout=20)
            self.assertEqual(compiled.returncode, 0, 'Native relay compilation failed')
            (registration / 'python-path.txt').write_text(sys.executable, encoding='utf-8')
            shutil.copyfile(Path(__file__).parent / 'launcher_fixture.py', directory / 'native_host.py')
            stream = io.BytesIO(); host.write_frame(stream, {'ok': True, 'text': 'Vietnamese synthetic Tiếng Việt'})
            host.write_frame(stream, {'ok': False, 'error': 'SYNTHETIC'})
            result = subprocess.run([str(exe), ORIGIN, '--parent-window=0'], input=stream.getvalue(), capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0); self.assertEqual(result.stdout, stream.getvalue()); self.assertEqual(result.stderr, b'')


if __name__ == "__main__": unittest.main()
