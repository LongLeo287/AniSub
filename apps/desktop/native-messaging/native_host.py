"""Bounded Chrome stdio framing -> current-user Windows named-pipe bridge. No logging."""
import ctypes
import json
import math
import re
import struct
import sys
import time
from pathlib import Path

MAX_FRAME = 65536
PIPE_NAME = r"\\.\pipe\AniSub.Desktop.v1"


class ProtocolError(Exception):
    pass


def read_exact(stream, count):
    chunks = bytearray()
    while len(chunks) < count:
        part = stream.read(count - len(chunks))
        if not part:
            if not chunks:
                return None
            raise ProtocolError("TRUNCATED_FRAME")
        chunks.extend(part)
    return bytes(chunks)


def read_frame(stream):
    prefix = read_exact(stream, 4)
    if prefix is None:
        return None
    size = struct.unpack("<I", prefix)[0]
    if not 0 < size <= MAX_FRAME:
        raise ProtocolError("MESSAGE_TOO_LARGE")
    data = read_exact(stream, size)
    if data is None:
        raise ProtocolError("TRUNCATED_FRAME")
    try:
        return json.loads(data.decode("utf-8"), parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, UnicodeError):
        raise ProtocolError("INVALID_JSON") from None


def write_frame(stream, value):
    data = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if len(data) > MAX_FRAME:
        raise ProtocolError("MESSAGE_TOO_LARGE")
    stream.write(struct.pack("<I", len(data)) + data)
    stream.flush()


def validate_origin(origin, allowlist):
    return bool(re.fullmatch(r"chrome-extension://[a-p]{32}/?", origin or "")) and origin.rstrip("/") + "/" in allowlist


def number(value):
    return isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value)


def validate_message(value):
    if not isinstance(value, dict):
        raise ProtocolError("INVALID_MESSAGE")
    kind, session = value.get("type"), value.get("session")
    if kind not in ("snapshot", "status", "close") or not isinstance(session, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", session):
        raise ProtocolError("INVALID_MESSAGE")
    for key in ("revision", "sequence"):
        if type(value.get(key)) is not int or not (0 if key == "revision" else 1) <= value[key] <= 9007199254740991:
            raise ProtocolError("INVALID_MESSAGE")
    result = {key: value[key] for key in ("type", "session", "revision", "sequence")}
    if kind == "snapshot":
        if not number(value.get("positionMs")) or value["positionMs"] < 0 or type(value.get("playing")) is not bool or not number(value.get("speed")) or not .5 <= value["speed"] <= 2 or value.get("language") not in ("vi", "en"):
            raise ProtocolError("UNSUPPORTED_INPUT")
        cues = value.get("cues")
        if not isinstance(cues, list) or len(cues) > 32:
            raise ProtocolError("INVALID_CUE")
        clean = []
        for cue in cues:
            if not isinstance(cue, dict) or not isinstance(cue.get("text"), str) or not number(cue.get("startMs")) or not number(cue.get("endMs")) or not 0 <= cue["startMs"] < cue["endMs"]:
                raise ProtocolError("INVALID_CUE")
            try:
                if len(cue["text"].encode("utf-16-le")) // 2 > 512:
                    raise ProtocolError("INVALID_CUE")
            except UnicodeError:
                raise ProtocolError("INVALID_CUE") from None
            clean.append({key: cue[key] for key in ("startMs", "endMs", "text")})
        result.update({key: value[key] for key in ("positionMs", "playing", "speed", "language")})
        result["cues"] = clean
    return result


def pipe_request(value, timeout_ms=3000):
    if sys.platform != "win32":
        return {"ok": False, "speaking": False, "error": "UNSUPPORTED_HOST"}
    deadline = time.monotonic() + timeout_ms / 1000
    from ctypes import wintypes as wt
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    class Overlapped(ctypes.Structure):
        _fields_ = [("Internal", ctypes.c_size_t), ("InternalHigh", ctypes.c_size_t), ("Offset", wt.DWORD), ("OffsetHigh", wt.DWORD), ("hEvent", wt.HANDLE)]
    kernel.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, wt.LPVOID, wt.DWORD, wt.DWORD, wt.HANDLE]
    kernel.CreateFileW.restype = wt.HANDLE
    kernel.CreateEventW.argtypes = [wt.LPVOID, wt.BOOL, wt.BOOL, wt.LPCWSTR]; kernel.CreateEventW.restype = wt.HANDLE
    kernel.ReadFile.argtypes = [wt.HANDLE, wt.LPVOID, wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.POINTER(Overlapped)]
    kernel.WriteFile.argtypes = kernel.ReadFile.argtypes
    kernel.WaitForSingleObject.argtypes = [wt.HANDLE, wt.DWORD]
    kernel.GetOverlappedResult.argtypes = [wt.HANDLE, ctypes.POINTER(Overlapped), ctypes.POINTER(wt.DWORD), wt.BOOL]
    kernel.CancelIoEx.argtypes = [wt.HANDLE, ctypes.POINTER(Overlapped)]
    kernel.CloseHandle.argtypes = [wt.HANDLE]
    kernel.WaitNamedPipeW.argtypes = [wt.LPCWSTR, wt.DWORD]
    handle = ctypes.c_void_p(-1).value
    # A per-request server has a brief no-instance interval between connections.
    # WaitNamedPipe returns immediately on FILE_NOT_FOUND; retry within the SAME deadline.
    while time.monotonic() < deadline:
        kernel.WaitNamedPipeW(PIPE_NAME, min(100, max(1, int((deadline-time.monotonic())*1000))))
        handle = kernel.CreateFileW(PIPE_NAME, 0xC0000000, 0, None, 3, 0x40000000, None)
        if handle != ctypes.c_void_p(-1).value:
            break
        failure = ctypes.get_last_error()
        if failure not in (2, 231):
            return {"ok": False, "speaking": False, "error": "PIPE_DENIED" if failure == 5 else "PIPE_FAILED"}
        time.sleep(min(.025, max(0, deadline-time.monotonic())))
    if handle == ctypes.c_void_p(-1).value:
        return {"ok": False, "speaking": False, "error": "DESKTOP_NOT_RUNNING"}
    event = kernel.CreateEventW(None, True, False, None)
    if not event:
        kernel.CloseHandle(handle)
        return {"ok": False, "speaking": False, "error": "PIPE_FAILED"}
    def transfer(fn, buffer, size):
        kernel.ResetEvent.argtypes = [wt.HANDLE]; kernel.ResetEvent(event)
        overlapped = Overlapped(); overlapped.hEvent = event
        count = wt.DWORD()
        if not fn(handle, buffer, size, ctypes.byref(count), ctypes.byref(overlapped)):
            if ctypes.get_last_error() != 997:
                raise ProtocolError("PIPE_FAILED")
            remaining_ms = max(0, int((deadline - time.monotonic()) * 1000))
            if kernel.WaitForSingleObject(event, remaining_ms) != 0:
                kernel.CancelIoEx(handle, ctypes.byref(overlapped))
                kernel.GetOverlappedResult(handle, ctypes.byref(overlapped), ctypes.byref(count), True)
                raise ProtocolError("TIMEOUT")
            if not kernel.GetOverlappedResult(handle, ctypes.byref(overlapped), ctypes.byref(count), False):
                raise ProtocolError("PIPE_FAILED")
        return count.value
    try:
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8") + b"\n"
        if len(payload) > MAX_FRAME:
            raise ProtocolError("MESSAGE_TOO_LARGE")
        buffer = ctypes.create_string_buffer(payload)
        if transfer(kernel.WriteFile, buffer, len(payload)) != len(payload):
            raise ProtocolError("PIPE_FAILED")
        data = bytearray()
        while b"\n" not in data:
            buffer = ctypes.create_string_buffer(min(4096, MAX_FRAME + 1 - len(data)))
            got = transfer(kernel.ReadFile, buffer, len(buffer))
            if not got:
                raise ProtocolError("PIPE_FAILED")
            data.extend(buffer.raw[:got])
            if len(data) > MAX_FRAME:
                raise ProtocolError("MESSAGE_TOO_LARGE")
        reply = json.loads(data.split(b"\n", 1)[0].decode("utf-8"))
        if not isinstance(reply, dict):
            raise ProtocolError("INVALID_REPLY")
        return {"ok": reply.get("ok") is True, "speaking": reply.get("speaking") is True,
                "translated": reply.get("translated", "")[:4096] if isinstance(reply.get("translated"), str) else "",
                "error": reply.get("error", "") if re.fullmatch(r"[A-Z0-9_]{0,80}", str(reply.get("error", ""))) else "PROVIDER_FAILED"}
    except (ValueError, UnicodeError):
        raise ProtocolError("INVALID_REPLY") from None
    finally:
        kernel.CloseHandle(event); kernel.CloseHandle(handle)


def run(input_stream, output_stream, origin, allowlist, request=pipe_request):
    if not validate_origin(origin, allowlist):
        write_frame(output_stream, {"ok": False, "speaking": False, "error": "UNAUTHORIZED"})
        return 1
    active = None
    last_sequence = 0
    revision = -1
    try:
        while True:
            raw = read_frame(input_stream)
            if raw is None:
                return 0
            try:
                message = validate_message(raw)
                if active is None and message["type"] != "snapshot":
                    raise ProtocolError("NO_SESSION")
                if active and message["session"] != active:
                    raise ProtocolError("SOURCE_BUSY")
                if message["sequence"] <= last_sequence or message["revision"] < revision:
                    raise ProtocolError("STALE_MESSAGE")
                active = message["session"]
                last_sequence, revision = message["sequence"], message["revision"]
                reply = request(message)
                write_frame(output_stream, reply)
                if message["type"] == "close":
                    active = None; last_sequence = 0; revision = -1
            except ProtocolError as error:
                write_frame(output_stream, {"ok": False, "speaking": False, "error": str(error)})
    except ProtocolError:
        return 1
    finally:
        if active:
            try:
                request({"type": "close", "session": active, "revision": max(0, revision), "sequence": last_sequence + 1})
            except Exception:
                pass


def main():
    if sys.platform == "win32":
        import msvcrt, os
        msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY); msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
    try:
        allowlist = json.loads((Path(__file__).parent / "registration" / "allowlist.json").read_text(encoding="utf-8"))["allowed_origins"]
    except (OSError, ValueError, KeyError):
        allowlist = []
    return run(sys.stdin.buffer, sys.stdout.buffer, sys.argv[1] if len(sys.argv) > 1 else "", allowlist)


if __name__ == "__main__":
    sys.exit(main())
