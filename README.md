# Distributed API Rate Limiter

Per-tenant API rate limiting on Spring Boot and Redis, with two pluggable algorithms,
atomic enforcement via Lua, circuit breaking, and a provisioned observability stack.

`Spring Boot 3.3` · `Spring Cloud Gateway` · `Redis 7` · `Resilience4j` · `Prometheus` · `Grafana` · `Java 21`

---

## The problem

Two gateway instances handle a request for the same tenant simultaneously. Both read the
remaining quota, both see `1`, both admit. Two requests were allowed against a quota of
one. This is a lost update, and it happens constantly under load — not rarely.

The fix is to make the read-modify-write indivisible by moving it into a Redis Lua script,
which Redis executes atomically.

**I measured the difference instead of asserting it:**

| Implementation | Admitted | Quota | Over-admission |
|---|---|---|---|
| Naive `GET` → compute → `SET` | **640** | 100 | **+540%** |
| Atomic Lua script | **100** | 100 | **0** |

64 threads, 640 requests, bucket capacity 100, same Redis. Three consecutive runs each:
the naive version admitted 640/640/640, the atomic version 100/100/100.

---

## Measured results

**Latency** — 5,000 calls per algorithm, loopback TCP:

| Algorithm | mean | p50 | p95 | p99 |
|---|---|---|---|---|
| Token bucket | 0.015 ms | 0.014 ms | 0.024 ms | 0.037 ms |
| Sliding window | 0.015 ms | 0.014 ms | 0.025 ms | 0.038 ms |

Effectively identical. Latency is not the reason to choose between them.

**Memory** — after 1,000 requests against one key:

| Algorithm | Per key | At 10k tenants |
|---|---|---|
| Token bucket | 96 bytes (constant) | ~0.9 MB |
| Sliding window | ~97,000 bytes (linear) | ~950 MB |

Around **1,000×** difference. This is the real trade-off, and it's a capacity decision rather
than a performance one.

---

## Architecture

```
client ──▶ Spring Cloud Gateway ──┬──▶ JWT validation (tenant identity)
  (JWT)                           ├──▶ tenant config  (Redis cache ◀─ Postgres)
                                  ├──▶ rate limit     (atomic Lua on Redis)
                                  └──▶ circuit breaker ──▶ service-a / service-b
                                              │
                                     Micrometer ──▶ Prometheus ──▶ Grafana
```

| Module | Purpose |
|---|---|
| `rate-limiter-core` | Algorithms, Lua scripts, metrics — a library, no web deps |
| `api-gateway` | Gateway, filter, JWT, tenant cache, admin endpoints |
| `service-a` / `service-b` | Downstreams with `/chaos` toggles for breaker demos |
| `infra` | Dockerfile, Prometheus config, provisioned Grafana dashboard |

---

## Quick start

```bash
docker compose up --build
```

| | |
|---|---|
| Gateway | http://localhost:8080 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

```bash
# Mint a demo token
TOKEN=$(curl -s "http://localhost:8080/dev/token?tenantId=acme-free&tier=FREE" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# Inspect quota headers
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping

# Exhaust the 60/min quota
for i in $(seq 1 70); do
  curl -s -o /dev/null -w "%{http_code} " \
    -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
done; echo
```

---

## Features

- **Two algorithms**, switchable per tenant without a restart (strategy pattern)
- **Atomic enforcement** — the whole check-and-decrement is one Lua script
- **Per-tenant, per-route quotas** — FREE / PRO / ENTERPRISE, plus per-tenant overrides
- **JWT-derived identity** — never a client-controlled header
- **Fail-open with visibility** — Redis outage admits traffic, increments a counter, sets `X-RateLimit-Degraded`
- **Circuit breaking** — Resilience4j, 5 failures in a 10-call window, structured 503 fallback
- **Provisioned Grafana** — 7 panels, appears automatically, no manual import
- **Testcontainers concurrency tests** — real Redis, `@RepeatedTest(5)`

### Response headers

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 43
X-RateLimit-Reset: 1727123456
Retry-After: 12              (on 429 only)
X-RateLimit-Degraded: true   (when Redis was unreachable)
```

---

## Chaos testing

```bash
# Circuit breaker
curl http://localhost:8081/chaos/on      # service-a fails; breaker opens
curl http://localhost:8081/chaos/off     # recovers automatically

# Redis outage — fail-open path
docker stop rl-redis
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/a/ping
# 200 with X-RateLimit-Degraded: true
docker start rl-redis
```

---

## Testing

```bash
mvn test
```

| Test | Proves |
|---|---|
| `TokenBucketConcurrencyTest` | 500 parallel callers never exceed capacity |
| `SlidingWindowLimiterTest` | 400 parallel callers admit exactly the limit |
| Refill test | Tokens return at the expected rate |
| Retry-After test | Rejections carry a usable backoff hint |

Both use Testcontainers against real Redis. A mock cannot exhibit the race these tests
exist to rule out.

---

## Configuration

```yaml
ratelimiter:
  enabled: true        # false runs the benchmark baseline with the limiter out of path
  fail-open: true      # false rejects with 503 when Redis is down
  key-ttl-seconds: 3600
  tenant-cache-ttl-seconds: 60
```

Seeded demo tenants: `acme-free`, `acme-pro`, `acme-enterprise`, `acme-sliding`
(same PRO quota on the sliding window, for side-by-side comparison), `acme-custom`
(FREE tier with a 120/min override).

---

## Design decisions

Covered in full in [DOC.md](DOC.md), including the reasoning behind each:

- Why Lua rather than `INCR` — and why a fixed-window counter has a boundary problem
- Why the clock is passed into the script rather than read inside it
- Why the token bucket is the default despite being less exact
- Why fail-open is the default, and when fail-closed is the right answer
- Why sorted-set members are `timestamp-uuid` rather than just the timestamp

## Limitations and scaling

Also in [DOC.md](DOC.md): single Redis instance, clock skew assumptions, unbounded
sliding-window memory within a window, and what changes at scale — Cluster with hash-tagged
keys, two-layer local/global limiting, Sentinel, `LISTEN/NOTIFY` for config propagation.

---

## License

MIT
