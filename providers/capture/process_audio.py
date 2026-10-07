"""WASAPI application loopback, selected PID tree only, bounded transient PCM.

Original ABI bindings based on Microsoft's documented ApplicationLoopback API.
No endpoint/system loopback code is present.
"""
import ctypes as C
from ctypes import wintypes as W
import sys
import threading
import time
import uuid

HRESULT = C.c_int32
CALL = getattr(C, 'WINFUNCTYPE', C.CFUNCTYPE)
_pending_callbacks = []  # Keep asynchronous callback ABI alive until late completion.

class Guid(C.Structure):
    _fields_ = [('data', C.c_ubyte * 16)]
    def __init__(self, value):
        super().__init__((C.c_ubyte * 16).from_buffer_copy(uuid.UUID(value).bytes_le))

def check(value):
    if value < 0:
        raise RuntimeError('PROCESS_AUDIO_UNAVAILABLE')

def method(pointer, index, result, *args):
    table = C.cast(pointer, C.POINTER(C.POINTER(C.c_void_p))).contents
    return CALL(result, C.c_void_p, *args)(table[index])

def release(pointer):
    if pointer: method(pointer, 2, W.ULONG)(pointer)

def capture_process(pid, seconds, on_chunk=None, stop_event=None):
    if sys.platform != 'win32' or sys.getwindowsversion().build < 20348:
        raise RuntimeError('PROCESS_AUDIO_UNSUPPORTED')
    if isinstance(pid, bool) or not isinstance(pid, int) or pid <= 0 or not 1 <= seconds <= 8:
        raise ValueError('INVALID_SOURCE')
    import numpy as np
    ole = C.WinDLL('ole32'); kernel = C.WinDLL('kernel32'); mm = C.WinDLL('Mmdevapi')
    ole.CoInitializeEx.restype = HRESULT
    initialized = ole.CoInitializeEx(None, 0) >= 0
    completed = threading.Event()
    result = {'hr': -1, 'client': None}
    callback_id = Guid('41D949AB-9862-444A-80F6-C261334DA5EB')
    unknown_id = Guid('00000000-0000-0000-C000-000000000046')
    agile_id = Guid('94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90')
    @CALL(HRESULT, C.c_void_p, C.POINTER(Guid), C.POINTER(C.c_void_p))
    def query(this, iid, output):
        if bytes(iid.contents.data) in (bytes(callback_id.data), bytes(unknown_id.data), bytes(agile_id.data)):
            output[0] = this
            return 0
        output[0] = None
        return -2147467262
    @CALL(W.ULONG, C.c_void_p)
    def add_ref(this): return 2
    @CALL(W.ULONG, C.c_void_p)
    def drop_ref(this): return 1
    @CALL(HRESULT, C.c_void_p, C.c_void_p)
    def complete(this, operation):
        try:
            hr, client = HRESULT(), C.c_void_p()
            outer = method(operation, 3, HRESULT, C.POINTER(HRESULT), C.POINTER(C.c_void_p))(operation, C.byref(hr), C.byref(client))
            result['hr'] = outer if outer < 0 else hr.value
            result['client'] = client
        finally:
            completed.set()
        return 0
    callbacks = (query, add_ref, drop_ref, complete)
    vtable = (C.c_void_p * 4)(*(C.cast(v, C.c_void_p).value for v in callbacks))
    callback = C.pointer(C.cast(vtable, C.c_void_p))
    class Params(C.Structure):
        _fields_ = [('type', W.DWORD), ('pid', W.DWORD), ('mode', W.DWORD)]
    class Blob(C.Structure):
        _fields_ = [('size', W.ULONG), ('data', C.c_void_p)]
    class Union(C.Union):
        _fields_ = [('blob', Blob), ('padding', C.c_ubyte * 16)]
    class Variant(C.Structure):
        _fields_ = [('vt', W.WORD), ('a', W.WORD), ('b', W.WORD), ('c', W.WORD), ('value', Union)]
    params = Params(1, pid, 0)  # INCLUDE_TARGET_PROCESS_TREE, never EXCLUDE.
    variant = Variant(); variant.vt = 65; variant.value.blob = Blob(C.sizeof(params), C.addressof(params))
    client_id = Guid('1CB9AD4C-DBFA-4C32-B178-C2F568A703B2')
    operation = C.c_void_p(); client = None; capture = C.c_void_p(); event = None; started = False
    mm.ActivateAudioInterfaceAsync.argtypes = [W.LPCWSTR, C.POINTER(Guid), C.POINTER(Variant), C.c_void_p, C.POINTER(C.c_void_p)]
    mm.ActivateAudioInterfaceAsync.restype = HRESULT
    kernel.CreateEventW.argtypes = [C.c_void_p, W.BOOL, W.BOOL, W.LPCWSTR]; kernel.CreateEventW.restype = W.HANDLE
    kernel.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]; kernel.CloseHandle.argtypes = [W.HANDLE]
    try:
        check(mm.ActivateAudioInterfaceAsync('VAD\\Process_Loopback', C.byref(client_id), C.byref(variant), callback, C.byref(operation)))
        if not completed.wait(5):
            # Activation cannot be cancelled by this API. Owner kills the isolated worker
            # on timeout; do not release callback memory while Windows may invoke it.
            _pending_callbacks.append((callbacks, vtable, callback, params, variant, operation))
            operation = None
            raise RuntimeError('PROCESS_AUDIO_TIMEOUT')
        check(result['hr']); client = result['client']
        class Wave(C.Structure):
            _pack_ = 1
            _fields_ = [('tag', W.WORD), ('channels', W.WORD), ('rate', W.DWORD), ('bytes', W.DWORD), ('align', W.WORD), ('bits', W.WORD), ('extra', W.WORD)]
        wave = Wave(1, 2, 48000, 192000, 4, 16, 0)
        check(method(client, 3, HRESULT, C.c_int, W.DWORD, C.c_int64, C.c_int64, C.POINTER(Wave), C.c_void_p)(client, 0, 0x60000, 0, 0, C.byref(wave), None))
        event = kernel.CreateEventW(None, False, False, None)
        if not event: raise RuntimeError('PROCESS_AUDIO_UNAVAILABLE')
        check(method(client, 13, HRESULT, W.HANDLE)(client, event))
        capture_id = Guid('C8ADBD64-E71E-48A0-A4DE-185C395CD317')
        check(method(client, 14, HRESULT, C.POINTER(Guid), C.POINTER(C.c_void_p))(client, C.byref(capture_id), C.byref(capture)))
        check(method(client, 10, HRESULT)(client)); started = True
        maximum = int(seconds * 48000)
        pcm = bytearray(); deadline = time.monotonic() + seconds + .5
        streaming = on_chunk is not None
        def convert(data):
            samples = np.frombuffer(data, dtype='<i2').reshape(-1, 2).astype(np.float32).mean(axis=1) / 32768
            return samples[:len(samples) // 3 * 3].reshape(-1, 3).mean(axis=1)
        while (streaming and not stop_event.is_set()) or (not streaming and len(pcm) < maximum * 4 and time.monotonic() < deadline):
            kernel.WaitForSingleObject(event, 100)
            frames = W.UINT()
            check(method(capture, 5, HRESULT, C.POINTER(W.UINT))(capture, C.byref(frames)))
            while frames.value:
                pointer, count, flags = C.c_void_p(), W.UINT(), W.DWORD()
                check(method(capture, 3, HRESULT, C.POINTER(C.c_void_p), C.POINTER(W.UINT), C.POINTER(W.DWORD), C.c_void_p, C.c_void_p)(capture, C.byref(pointer), C.byref(count), C.byref(flags), None, None))
                try:
                    length = count.value * 4
                    if length > 192000 * 2: raise RuntimeError('PROCESS_AUDIO_UNAVAILABLE')
                    packet = bytes(length) if flags.value & 2 else C.string_at(pointer, length)
                    if streaming:
                        cursor = 0
                        while cursor < len(packet):
                            accepted = min(maximum * 4 - len(pcm), len(packet) - cursor)
                            pcm.extend(packet[cursor:cursor + accepted]); cursor += accepted
                            if len(pcm) == maximum * 4:
                                on_chunk(convert(pcm)); pcm.clear()
                    else:
                        pcm.extend(packet[:maximum * 4 - len(pcm)])
                finally:
                    check(method(capture, 4, HRESULT, W.UINT)(capture, count))
                if not streaming and len(pcm) >= maximum * 4: break
                if streaming and stop_event.is_set(): break
                check(method(capture, 5, HRESULT, C.POINTER(W.UINT))(capture, C.byref(frames)))
        if streaming: return None
        if not pcm: return np.zeros(16000, dtype=np.float32)
        # Exact 48k -> 16k box low-pass decimation avoids an extra resampling dependency.
        return convert(pcm)
    finally:
        if started:
            method(client, 11, HRESULT)(client)
        release(capture); release(client); release(operation)
        if event: kernel.CloseHandle(event)
        if initialized:
            ole.CoUninitialize()
