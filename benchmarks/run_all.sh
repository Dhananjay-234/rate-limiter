#!/usr/bin/env bash
#
# Reproduces every number in DOC.md section 3 against a local Redis.
# These are the results quoted in the README — run this before you publish so
# you're quoting numbers from your own machine rather than mine.
#
# Usage:  ./benchmarks/run_all.sh [port]
#
set -euo pipefail

PORT="${1:-6399}"
SCRIPTS_DIR="$(cd "$(dirname "$0")/../rate-limiter-core/src/main/resources/scripts" && pwd)"
BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"

command -v redis-server >/dev/null || { echo "redis-server not found"; exit 1; }
command -v python3      >/dev/null || { echo "python3 not found"; exit 1; }

if ! redis-cli -p "$PORT" ping >/dev/null 2>&1; then
  echo "Starting Redis on port $PORT..."
  redis-server --daemonize yes --port "$PORT" --save '' >/dev/null
  sleep 2
fi

redis-cli -p "$PORT" ping >/dev/null || { echo "Redis not reachable on $PORT"; exit 1; }

cd "$SCRIPTS_DIR"

hr() { printf '%*s\n' 72 '' | tr ' ' '-'; }

echo
hr
echo "1. ATOMICITY — atomic Lua script"
hr
redis-cli -p "$PORT" FLUSHALL >/dev/null
python3 "$BENCH_DIR/atomicity_proof.py"

echo
hr
echo "2. ATOMICITY — naive GET/SET counter-example (same workload)"
hr
redis-cli -p "$PORT" FLUSHALL >/dev/null
python3 "$BENCH_DIR/naive_counterexample.py"

echo
hr
echo "3. LATENCY — both algorithms"
hr
redis-cli -p "$PORT" FLUSHALL >/dev/null
python3 "$BENCH_DIR/latency.py"

echo
hr
echo "4. MEMORY — both algorithms"
hr
redis-cli -p "$PORT" FLUSHALL >/dev/null
python3 "$BENCH_DIR/memory.py"

echo
hr
echo "Done. Update the tables in README.md and DOC.md with these numbers."
hr
