"""
Proves the atomicity claim: fire N requests in parallel at a bucket of capacity C
and assert exactly C are admitted. A non-atomic GET/SET implementation over-admits.
"""
import socket, threading, time

HOST, PORT = "127.0.0.1", 6399

def resp_cmd(*args):
    out = [f"*{len(args)}\r\n".encode()]
    for a in args:
        b = str(a).encode()
        out.append(b"$%d\r\n%s\r\n" % (len(b), b))
    return b"".join(out)

script = open("token_bucket.lua").read()

# Load the script once, get SHA
s = socket.create_connection((HOST, PORT))
s.sendall(resp_cmd("SCRIPT", "LOAD", script))
sha = s.recv(4096).decode().strip().lstrip("$0123456789\r\n").split("\r\n")[0]
s.close()
print(f"script SHA: {sha}")

CAPACITY = 100
THREADS = 64
CALLS_PER_THREAD = 10   # 640 total attempts

results = {"allowed": 0, "rejected": 0}
lock = threading.Lock()
barrier = threading.Barrier(THREADS)
now = int(time.time() * 1000)

def worker():
    conn = socket.create_connection((HOST, PORT))
    local_a = local_r = 0
    barrier.wait()   # all threads start at the same instant
    for _ in range(CALLS_PER_THREAD):
        conn.sendall(resp_cmd("EVALSHA", sha, 1, "tb:concurrency",
                              CAPACITY, 0.0001, now, 3600))
        data = conn.recv(4096).decode()
        # reply is a RESP array; first element is the allowed flag
        first = data.split("\r\n")[1]
        if first == ":1":
            local_a += 1
        else:
            local_r += 1
    conn.close()
    with lock:
        results["allowed"] += local_a
        results["rejected"] += local_r

threads = [threading.Thread(target=worker) for _ in range(THREADS)]
for t in threads: t.start()
for t in threads: t.join()

total = results["allowed"] + results["rejected"]
print(f"\ncapacity          : {CAPACITY}")
print(f"parallel threads  : {THREADS}")
print(f"total attempts    : {total}")
print(f"admitted          : {results['allowed']}")
print(f"rejected          : {results['rejected']}")

assert results["allowed"] == CAPACITY, \
    f"OVER-ADMISSION: expected exactly {CAPACITY}, got {results['allowed']}"
assert total == THREADS * CALLS_PER_THREAD
print("\nPASS — admitted count is exactly the bucket capacity under 64-way parallelism.")
