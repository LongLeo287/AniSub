"""Synthetic binary relay fixture; no registered host/config/model dependencies."""
import struct
import sys
while True:
    prefix = sys.stdin.buffer.read(4)
    if not prefix:
        break
    size = struct.unpack('<I', prefix)[0]
    if size > 65536:
        sys.exit(1)
    body = sys.stdin.buffer.read(size)
    sys.stdout.buffer.write(prefix + body); sys.stdout.buffer.flush()
