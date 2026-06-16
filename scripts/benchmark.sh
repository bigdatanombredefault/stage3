#!/usr/bin/env bash
# benchmark.sh — Stage 3 benchmark suite
#
# Runs four test phases (§4.4 of the project guide):
#   1. Baseline   — single-node ingestion + search latency
#   2. Scaling    — repeat with all nodes; compare throughput
#   3. Load       — concurrent search queries via wrk (avg, p95, max latency)
#   4. Failure    — stop one node mid-load; measure recovery time and error rate
#
# Results are written to benchmarks/results/<timestamp>/
#
# Usage:
#   bash scripts/benchmark.sh --master 10.26.14.200 --workers "10.26.14.200 10.26.14.201 10.26.14.202"
#
# Prerequisites:
#   - wrk installed (apt install wrk / brew install wrk)
#   - At least 50 books already indexed (run lab_ingest_first_n.sh first)
#   - All nodes running and Hazelcast cluster formed

set -euo pipefail

MASTER_IP=""
WORKERS_STR=""
INGEST_COUNT=50
WRK_DURATION=30
WRK_THREADS=4
WRK_CONNECTIONS=50
RESULTS_DIR="benchmarks/results/$(date +'%Y%m%d_%H%M%S')"
SEARCH_TERM="the"

usage() {
  cat <<EOF
Usage: bash scripts/benchmark.sh --master <IP> --workers "<IP1> <IP2> ..."

Options:
  --master      IP of the master node (ActiveMQ + Nginx)
  --workers     Space-separated list of ALL worker node IPs (including master)
  --count       Books to ingest per phase (default: $INGEST_COUNT)
  --duration    wrk test duration in seconds (default: $WRK_DURATION)
  --term        Search term for load test (default: $SEARCH_TERM)

Example:
  bash scripts/benchmark.sh --master 10.26.14.200 --workers "10.26.14.200 10.26.14.201 10.26.14.202"
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --master)    MASTER_IP="${2:-}"; shift 2 ;;
    --workers)   WORKERS_STR="${2:-}"; shift 2 ;;
    --count)     INGEST_COUNT="${2:-}"; shift 2 ;;
    --duration)  WRK_DURATION="${2:-}"; shift 2 ;;
    --term)      SEARCH_TERM="${2:-}"; shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$MASTER_IP" || -z "$WORKERS_STR" ]]; then
  echo "ERROR: --master and --workers are required" >&2
  usage
  exit 2
fi

if ! command -v wrk &>/dev/null; then
  echo "ERROR: wrk is not installed. Install with: apt install wrk" >&2
  exit 1
fi

read -r -a WORKERS <<<"$WORKERS_STR"
FIRST_WORKER="${WORKERS[0]}"

log()  { printf '\n[%s] %s\n' "$(date +'%H:%M:%S')" "$*"; }
hdr()  { printf '\n══════════════════════════════════════\n%s\n══════════════════════════════════════\n' "$*"; }
save() { local file="$1"; shift; printf '%s\n' "$@" | tee -a "$file"; }

mkdir -p "$RESULTS_DIR"
SUMMARY="$RESULTS_DIR/summary.txt"

log "Results will be written to $RESULTS_DIR"
log "Master: $MASTER_IP | Workers: ${WORKERS[*]}"
save "$SUMMARY" "Benchmark run: $(date)" "Master: $MASTER_IP" "Workers: ${WORKERS[*]}" "---"

# ──────────────────────────────────────────────────────────────────────────────
# PHASE 0: Pre-flight checks
# ──────────────────────────────────────────────────────────────────────────────
hdr "PHASE 0 — Pre-flight checks"
log "Checking health of all nodes..."
ALL_OK=true
for w in "${WORKERS[@]}"; do
  status=$(curl -s -o /dev/null -w "%{http_code}" "http://$w:8082/health" || echo "000")
  if [[ "$status" == "200" ]]; then
    log "  $w:8082 (search) → OK"
  else
    log "  $w:8082 (search) → FAILED (http $status)"
    ALL_OK=false
  fi
done
if [[ "$ALL_OK" != "true" ]]; then
  echo "One or more search nodes are not healthy. Start the cluster first." >&2
  exit 1
fi

log "Checking initial index stats..."
stats=$(curl -s "http://$FIRST_WORKER:8082/stats" || echo '{}')
log "  Stats: $stats"
save "$SUMMARY" "Pre-flight stats: $stats"

# ──────────────────────────────────────────────────────────────────────────────
# PHASE 1: Baseline (single-node ingest + search latency)
# ──────────────────────────────────────────────────────────────────────────────
hdr "PHASE 1 — Baseline (1 node)"
BASELINE_FILE="$RESULTS_DIR/phase1_baseline.txt"
log "Ingesting $INGEST_COUNT books on $FIRST_WORKER only..."

START_TS=$(date +%s)
bash scripts/lab_ingest_first_n.sh \
  --nodes "$FIRST_WORKER" \
  --count "$INGEST_COUNT" \
  --start 1 \
  --concurrency 10 \
  --async true 2>&1 | tee "$BASELINE_FILE"
END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

log "Waiting 15s for indexing to complete..."
sleep 15

INDEXED_AFTER=$(curl -s "http://$FIRST_WORKER:8082/stats" || echo '{}')
INGEST_RATE=$(echo "scale=2; $INGEST_COUNT / $ELAPSED" | bc 2>/dev/null || echo "N/A")

log "  Elapsed: ${ELAPSED}s | Approx ingestion rate: ${INGEST_RATE} docs/s"
log "  Stats after baseline: $INDEXED_AFTER"
save "$SUMMARY" "" "=== PHASE 1: Baseline ===" \
  "  Nodes: 1 ($FIRST_WORKER)" \
  "  Books dispatched: $INGEST_COUNT" \
  "  Elapsed: ${ELAPSED}s" \
  "  Approx ingestion rate: ${INGEST_RATE} docs/s" \
  "  Stats: $INDEXED_AFTER"

log "Measuring baseline search latency (single query)..."
for i in 1 2 3; do
  latency=$(curl -s -o /dev/null -w "%{time_total}" "http://$FIRST_WORKER:8082/search?q=${SEARCH_TERM}&limit=10" || echo "ERR")
  log "  Query $i latency: ${latency}s"
  save "$SUMMARY" "  Baseline query $i: ${latency}s"
done

# ──────────────────────────────────────────────────────────────────────────────
# PHASE 2: Scaling (all nodes, measure throughput increase)
# ──────────────────────────────────────────────────────────────────────────────
hdr "PHASE 2 — Scaling (${#WORKERS[@]} nodes)"
SCALING_FILE="$RESULTS_DIR/phase2_scaling.txt"
log "Ingesting $INGEST_COUNT more books across all ${#WORKERS[@]} nodes..."

START_TS=$(date +%s)
bash scripts/lab_ingest_first_n.sh \
  --nodes "$WORKERS_STR" \
  --count "$INGEST_COUNT" \
  --start $((INGEST_COUNT + 1)) \
  --concurrency 30 \
  --async true 2>&1 | tee "$SCALING_FILE"
END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

log "Waiting 15s for indexing..."
sleep 15

STATS_AFTER_SCALING=$(curl -s "http://$FIRST_WORKER:8082/stats" || echo '{}')
INGEST_RATE_SCALED=$(echo "scale=2; $INGEST_COUNT / $ELAPSED" | bc 2>/dev/null || echo "N/A")

log "  Elapsed: ${ELAPSED}s | Approx ingestion rate: ${INGEST_RATE_SCALED} docs/s"
log "  Stats: $STATS_AFTER_SCALING"
save "$SUMMARY" "" "=== PHASE 2: Scaling ===" \
  "  Nodes: ${#WORKERS[@]} (${WORKERS[*]})" \
  "  Books dispatched: $INGEST_COUNT" \
  "  Elapsed: ${ELAPSED}s" \
  "  Approx ingestion rate: ${INGEST_RATE_SCALED} docs/s" \
  "  Stats: $STATS_AFTER_SCALING"

# ──────────────────────────────────────────────────────────────────────────────
# PHASE 3: Load test (concurrent search queries via wrk)
# ──────────────────────────────────────────────────────────────────────────────
hdr "PHASE 3 — Load test (wrk, ${WRK_DURATION}s)"
LOAD_FILE="$RESULTS_DIR/phase3_load.txt"

log "Running wrk against Nginx (http://$MASTER_IP/search?q=$SEARCH_TERM)..."
log "  Threads: $WRK_THREADS | Connections: $WRK_CONNECTIONS | Duration: ${WRK_DURATION}s"

wrk -t "$WRK_THREADS" \
    -c "$WRK_CONNECTIONS" \
    -d "${WRK_DURATION}s" \
    --latency \
    "http://$MASTER_IP/search?q=${SEARCH_TERM}&limit=10" \
    2>&1 | tee "$LOAD_FILE"

log "wrk complete. Results saved to $LOAD_FILE"

# Also collect docker stats snapshot from master
log "Collecting docker stats snapshot from master..."
DOCKER_STATS_FILE="$RESULTS_DIR/phase3_docker_stats.txt"
# Run docker stats for 5 seconds, non-streaming (capture a few samples)
timeout 6 docker stats --no-stream 2>/dev/null | tee "$DOCKER_STATS_FILE" || true

# Extract wrk key metrics from output
AVG_LATENCY=$(grep "Latency" "$LOAD_FILE" | grep -v "Distribution" | awk '{print $2}' || echo "N/A")
P99_LATENCY=$(grep "99%" "$LOAD_FILE" | awk '{print $2}' || echo "N/A")
REQUESTS_SEC=$(grep "Requests/sec" "$LOAD_FILE" | awk '{print $2}' || echo "N/A")

save "$SUMMARY" "" "=== PHASE 3: Load test ===" \
  "  Tool: wrk" \
  "  Target: http://$MASTER_IP/search?q=$SEARCH_TERM" \
  "  Threads: $WRK_THREADS | Connections: $WRK_CONNECTIONS | Duration: ${WRK_DURATION}s" \
  "  Avg latency: $AVG_LATENCY" \
  "  p99 latency: $P99_LATENCY  (wrk --latency reports 50/75/90/99 buckets; p95 not available)" \
  "  Requests/sec: $REQUESTS_SEC" \
  "  Full wrk output: phase3_load.txt"

# ──────────────────────────────────────────────────────────────────────────────
# PHASE 4: Failure test (stop one node, measure recovery)
# ──────────────────────────────────────────────────────────────────────────────
hdr "PHASE 4 — Failure test"
FAILURE_FILE="$RESULTS_DIR/phase4_failure.txt"

if [[ ${#WORKERS[@]} -lt 2 ]]; then
  log "SKIPPED: need at least 2 worker nodes to run failure test."
  save "$SUMMARY" "" "=== PHASE 4: Failure test === SKIPPED (need >= 2 nodes)"
else
  VICTIM="${WORKERS[1]}"   # second worker in the list
  log "Simulating failure: stopping search service on $VICTIM..."
  log "(You must run this command on $VICTIM): docker compose --profile node stop search"
  log ""
  log "After stopping the victim, this script will probe Nginx to measure recovery..."
  log "Press Enter when you have stopped the search service on $VICTIM."
  read -r

  FAILURE_TS=$(date +%s)
  RECOVERED=false
  RECOVERY_TIME=0

  for i in $(seq 1 30); do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      "http://$MASTER_IP/search?q=${SEARCH_TERM}&limit=5" || echo "000")
    UPSTREAM=$(curl -sI "http://$MASTER_IP/search?q=${SEARCH_TERM}&limit=5" \
      2>/dev/null | grep -i 'X-Upstream-Addr' | tr -d '\r' || echo "unknown")
    ELAPSED_RECOVERY=$(( $(date +%s) - FAILURE_TS ))
    printf '[%s] attempt %d — HTTP %s — upstream: %s\n' \
      "$(date +'%H:%M:%S')" "$i" "$STATUS" "$UPSTREAM" | tee -a "$FAILURE_FILE"

    if [[ "$STATUS" == "200" ]]; then
      RECOVERED=true
      RECOVERY_TIME=$ELAPSED_RECOVERY
      break
    fi
    sleep 1
  done

  if [[ "$RECOVERED" == "true" ]]; then
    log "RECOVERED in ${RECOVERY_TIME}s — requests rerouted to healthy nodes."
    save "$SUMMARY" "" "=== PHASE 4: Failure test ===" \
      "  Victim: $VICTIM (search service stopped)" \
      "  Result: RECOVERED" \
      "  Recovery time: ${RECOVERY_TIME}s"
  else
    log "WARNING: did not recover within 30 probe attempts."
    save "$SUMMARY" "" "=== PHASE 4: Failure test ===" \
      "  Victim: $VICTIM (search service stopped)" \
      "  Result: DID NOT RECOVER within 30s — check logs"
  fi
fi

# ──────────────────────────────────────────────────────────────────────────────
# Final summary
# ──────────────────────────────────────────────────────────────────────────────
hdr "Benchmark complete"
log "Results directory: $RESULTS_DIR"
log "Summary:"
cat "$SUMMARY"
