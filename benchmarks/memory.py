"""Memory footprint per tenant key: token bucket vs sliding window."""
import socket, time

HOST, PORT = "127.0.0.1", 6399

def resp_cmd(*args):
    out = [f"*{len(args)}\r\n".encode()]
    for a in args:
        b = str(a).encode()
        out.append(b"$%d\r\n%s\r\n" % (len(b), b))
    return b"".join(out)

conn = socket.create_connection((HOST, PORT))

def cmd(*args):
    conn.sendall(resp_cmd(*args))
    return conn.recv(65536).decode()

def load(path):
    r = cmd("SCRIPT", "LOAD", open(path).read())
    return r.strip().lstrip("$0123456789\r\n").split("\r\n")[0]

tb, sw = load("token_bucket.lua"), load("sliding_window.lua")
cmd("FLUSHALL")
now = int(time.time() * 1000)

LIMIT = 1000
for i in range(LIMIT):
    cmd("EVALSHA", tb, 1, "mem:tb", 1_000_000, 1000, now + i, 3600)
    cmd("EVALSHA", sw, 1, "mem:sw", 1_000_000, 60000, now + i, f"member-{i}", 3600)

def usage(key):
    r = cmd("MEMORY", "USAGE", key)
    return int(r.strip().lstrip(":").split("\r\n")[0])

tb_bytes, sw_bytes = usage("mem:tb"), usage("mem:sw")

print(f"after {LIMIT} requests against one key:\n")
print(f"  token_bucket   : {tb_bytes:>7,} bytes  (2 hash fields, constant)")
print(f"  sliding_window : {sw_bytes:>7,} bytes  ({LIMIT} sorted-set members)")
print(f"\n  sliding window uses {sw_bytes / tb_bytes:.0f}x more memory per key")
print(f"  per-request cost: ~{sw_bytes / LIMIT:.0f} bytes vs ~0 bytes")
print(f"\nAt 10,000 tenants x 1,000 req/min window:")
print(f"  token_bucket   : {tb_bytes * 10000 / 1024 / 1024:>8.1f} MB")
print(f"  sliding_window : {sw_bytes * 10000 / 1024 / 1024:>8.1f} MB")
conn.close()
