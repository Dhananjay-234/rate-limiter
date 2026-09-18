"""
Counter-example: the SAME test against a naive GET -> compute -> SET limiter
implemented in the client instead of a Lua script. This is what most tutorial
projects do, and it over-admits badly under parallelism.
"""
import socket, threading, time

HOST, PORT = "127.0.0.1", 6399

def resp_cmd(*args):
    out = [f"*{len(args)}\r\n".encode()]
    for a in args:
        b = str(a).encode()
        out.append(b"$%d\r\n%s\r\n" % (len(b), b))
    return b"".join(out)

CAPACITY = 100
THREADS = 64
CALLS_PER_THREAD = 10

results = {"allowed": 0, "rejected": 0}
lock = threading.Lock()
barrier = threading.Barrier(THREADS)

def read_reply(conn):
    return conn.recv(4096).decode()

def worker():
    conn = socket.create_connection((HOST, PORT))
    local_a = local_r = 0
    barrier.wait()
    for _ in range(CALLS_PER_THREAD):
        # ---- the race window is between these two commands ----
        conn.sendall(resp_cmd("GET", "naive:bucket"))
        raw = read_reply(conn)
        try:
            tokens = int(raw.split("\r\n")[1])
        except (ValueError, IndexError):
            tokens = CAPACITY

        if tokens >= 1:
            conn.sendall(resp_cmd("SET", "naive:bucket", tokens - 1))
            read_reply(conn)
            local_a += 1
        else:
            local_r += 1
        # -------------------------------------------------------
    conn.close()
    with lock:
        results["allowed"] += local_a
        results["rejected"] += local_r

s = socket.create_connection((HOST, PORT))
s.sendall(resp_cmd("SET", "naive:bucket", CAPACITY))
s.recv(4096)
s.close()

threads = [threading.Thread(target=worker) for _ in range(THREADS)]
for t in threads: t.start()
for t in threads: t.join()

over = results["allowed"] - CAPACITY
print(f"capacity          : {CAPACITY}")
print(f"admitted          : {results['allowed']}")
print(f"OVER-ADMITTED BY  : {over}  ({over / CAPACITY * 100:.0f}% over quota)")
print("\nSame workload, same Redis. The only difference is atomicity.")
