"""Measure per-call latency of each Lua script against a local Redis."""
import socket, time, statistics

HOST, PORT = "127.0.0.1", 6399

def resp_cmd(*args):
    out = [f"*{len(args)}\r\n".encode()]
    for a in args:
        b = str(a).encode()
        out.append(b"$%d\r\n%s\r\n" % (len(b), b))
    return b"".join(out)

def load(path):
    s = socket.create_connection((HOST, PORT))
    s.sendall(resp_cmd("SCRIPT", "LOAD", open(path).read()))
    sha = s.recv(4096).decode().strip().lstrip("$0123456789\r\n").split("\r\n")[0]
    s.close()
    return sha

def bench(name, sha, argv_fn, n=5000):
    conn = socket.create_connection((HOST, PORT))
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    samples = []
    for i in range(n):
        args = argv_fn(i)
        t0 = time.perf_counter()
        conn.sendall(resp_cmd("EVALSHA", sha, 1, *args))
        conn.recv(4096)
        samples.append((time.perf_counter() - t0) * 1000)
    conn.close()
    samples.sort()
    p = lambda q: samples[int(len(samples) * q)]
    print(f"{name:20s} mean={statistics.mean(samples):.3f}ms  "
          f"p50={p(0.50):.3f}ms  p95={p(0.95):.3f}ms  p99={p(0.99):.3f}ms")
    return statistics.mean(samples)

now = int(time.time() * 1000)
tb = load("token_bucket.lua")
sw = load("sliding_window.lua")

s = socket.create_connection((HOST, PORT)); s.sendall(resp_cmd("FLUSHALL")); s.recv(100); s.close()

print("Local Redis, 5000 calls each, loopback TCP\n")
# High capacity so we measure the allowed path, not the reject path
m1 = bench("token_bucket",   tb, lambda i: ("bench:tb", 1_000_000, 1000, now + i, 3600))
m2 = bench("sliding_window", sw, lambda i: ("bench:sw", 1_000_000, 60000, now + i, f"m{i}", 3600))

print(f"\nsliding window costs {(m2/m1 - 1) * 100:.0f}% more per call than token bucket")
