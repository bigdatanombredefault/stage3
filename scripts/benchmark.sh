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
INGEST_START=1
WRK_DURATION=30
WRK_THREADS=4
WRK_CONNECTIONS=50
RESULTS_DIR="benchmarks/results/$(date +'%Y%m%d_%H%M%S')"
SEARCH_TERM="the"
WAIT_SECS=45          # seconds to wait after dispatch for async downloads to index
CONFIG_LABEL=""       # human label for this run in the CSV (e.g. 1node, 3node, 6node)
CSV_FILE="benchmarks/results/metrics.csv"

usage() {
  cat <<EOF
Usage: bash scripts/benchmark.sh --master <IP> --workers "<IP1> <IP2> ..." [options]

Options:
  --master        IP of the master node (ActiveMQ + Nginx)
  --workers       Space-separated list of ALL worker node IPs (including master)
  --count         Books to ingest per phase (default: $INGEST_COUNT)
  --ingest-start  First book ID to ingest (default: $INGEST_START)
                  Set this to a fresh range for each scaling config so you
                  never re-ingest already-cached books (e.g. 301, 401, 501).
  --duration      Load test duration in seconds (default: $WRK_DURATION)
  --term          Search term for load test (default: $SEARCH_TERM)
  --label         Label for this run in metrics.csv (default: <N>node)

Example (3 scaling configs, each using 50 fresh books):
  bash scripts/benchmark.sh --master IP --workers "IP"             --ingest-start 301 --count 50 --label 1node
  bash scripts/benchmark.sh --master IP --workers "IP IP2 IP3"    --ingest-start 351 --count 50 --label 3node
  bash scripts/benchmark.sh --master IP --workers "IP IP2 .. IP6" --ingest-start 401 --count 50 --label 6node
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --master)         MASTER_IP="${2:-}";    shift 2 ;;
    --workers)        WORKERS_STR="${2:-}";  shift 2 ;;
    --count)          INGEST_COUNT="${2:-}"; shift 2 ;;
    --ingest-start)   INGEST_START="${2:-}"; shift 2 ;;
    --duration)       WRK_DURATION="${2:-}"; shift 2 ;;
    --term)           SEARCH_TERM="${2:-}";  shift 2 ;;
    --label)          CONFIG_LABEL="${2:-}";  shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$MASTER_IP" || -z "$WORKERS_STR" ]]; then
  echo "ERROR: --master and --workers are required" >&2
  usage
  exit 2
fi

log()  { printf '\n[%s] %s\n' "$(date +'%H:%M:%S')" "$*"; }
hdr()  { printf '\n══════════════════════════════════════\n%s\n══════════════════════════════════════\n' "$*"; }
save() { local file="$1"; shift; printf '%s\n' "$@" | tee -a "$file"; }
parse_stat() {
  # Extract integer value of a key from a simple JSON string without jq.
  # Usage: parse_stat '{"total_books":15,"unique_words":20005}' total_books
  local json="$1" key="$2"
  printf '%s' "$json" | grep -o "\"$key\":[0-9]*" | grep -o '[0-9]*$' || echo "0"
}

read -r -a WORKERS <<<"$WORKERS_STR"
FIRST_WORKER="${WORKERS[0]}"
[[ -z "$CONFIG_LABEL" ]] && CONFIG_LABEL="${#WORKERS[@]}node"

LOAD_TOOL=""
if command -v wrk &>/dev/null; then
  LOAD_TOOL="wrk"
elif powershell.exe -Command "exit 0" &>/dev/null 2>&1 || pwsh -Command "exit 0" &>/dev/null 2>&1; then
  LOAD_TOOL="powershell"
  PS_EXE=$(command -v pwsh 2>/dev/null || echo "powershell.exe")
else
  LOAD_TOOL="curl"
  echo "WARNING: wrk and PowerShell not found. Phase 3 will use a sequential curl fallback (avg latency only)." >&2
fi
log "Load test tool: $LOAD_TOOL"

mkdir -p "$RESULTS_DIR"
SUMMARY="$RESULTS_DIR/summary.txt"

# Initialise all metric variables so the CSV row is always complete
BOOKS_INDEXED_P1=0; WORDS_INDEXED_P1=0; INGEST_RATE="N/A"; INDEX_THROUGHPUT_P1="N/A"
BOOKS_INDEXED_P2=0; WORDS_INDEXED_P2=0; INGEST_RATE_SCALED="N/A"; INDEX_THROUGHPUT_P2="N/A"
AVG_LATENCY="N/A"; P95_LATENCY="N/A"; P99_LATENCY="N/A"; MAX_LATENCY="N/A"; REQUESTS_SEC="N/A"
CPU_INDEXER="N/A"; CPU_SEARCH="N/A"; MEM_INDEXER="N/A"; MEM_SEARCH="N/A"
RECOVERY_TIME="N/A"

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

STATS_P1_BEFORE=$(curl -s "http://$FIRST_WORKER:8082/stats" || echo '{}')
BOOKS_P1_BEFORE=$(parse_stat "$STATS_P1_BEFORE" "total_books")
WORDS_P1_BEFORE=$(parse_stat "$STATS_P1_BEFORE" "unique_words")

START_TS=$(date +%s)
bash scripts/lab_ingest_first_n.sh \
  --nodes "$FIRST_WORKER" \
  --count "$INGEST_COUNT" \
  --start "$INGEST_START" \
  --concurrency 10 \
  --async true 2>&1 | tee "$BASELINE_FILE"
END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

log "Waiting ${WAIT_SECS}s for async downloads and indexing to complete..."
sleep "$WAIT_SECS"

STATS_P1_AFTER=$(curl -s "http://$FIRST_WORKER:8082/stats" || echo '{}')
BOOKS_P1_AFTER=$(parse_stat "$STATS_P1_AFTER" "total_books")
WORDS_P1_AFTER=$(parse_stat "$STATS_P1_AFTER" "unique_words")
BOOKS_INDEXED_P1=$((BOOKS_P1_AFTER - BOOKS_P1_BEFORE))
WORDS_INDEXED_P1=$((WORDS_P1_AFTER - WORDS_P1_BEFORE))
TOTAL_P1=$((ELAPSED + WAIT_SECS))
INGEST_RATE=$(awk "BEGIN{printf \"%.2f\", $BOOKS_INDEXED_P1 / $TOTAL_P1}" 2>/dev/null || echo "N/A")
INDEX_THROUGHPUT_P1=$(awk "BEGIN{printf \"%.0f\", $WORDS_INDEXED_P1 / $TOTAL_P1}" 2>/dev/null || echo "N/A")

log "  Dispatch: ${ELAPSED}s | Total window (dispatch+wait): ${TOTAL_P1}s"
log "  Books indexed: ${BOOKS_INDEXED_P1} of ${INGEST_COUNT} dispatched | Rate: ${INGEST_RATE} docs/s"
log "  Indexing throughput: ${INDEX_THROUGHPUT_P1} tokens/s  (unique_words delta / window)"
log "  Stats after: $STATS_P1_AFTER"
save "$SUMMARY" "" "=== PHASE 1: Baseline ===" \
  "  Nodes: 1 ($FIRST_WORKER)" \
  "  Books dispatched: $INGEST_COUNT" \
  "  Books indexed in window: $BOOKS_INDEXED_P1" \
  "  Dispatch elapsed: ${ELAPSED}s  |  Total window: ${TOTAL_P1}s" \
  "  Ingestion rate: ${INGEST_RATE} docs/s  (books fully indexed / total window)" \
  "  Indexing throughput: ${INDEX_THROUGHPUT_P1} tokens/s  (unique_words delta / total window)" \
  "  Stats: $STATS_P1_AFTER"

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

STATS_P2_BEFORE=$(curl -s "http://$FIRST_WORKER:8082/stats" || echo '{}')
BOOKS_P2_BEFORE=$(parse_stat "$STATS_P2_BEFORE" "total_books")
WORDS_P2_BEFORE=$(parse_stat "$STATS_P2_BEFORE" "unique_words")

START_TS=$(date +%s)
bash scripts/lab_ingest_first_n.sh \
  --nodes "$WORKERS_STR" \
  --count "$INGEST_COUNT" \
  --start $((INGEST_START + INGEST_COUNT)) \
  --concurrency 30 \
  --async true 2>&1 | tee "$SCALING_FILE"
END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

log "Waiting ${WAIT_SECS}s for async downloads and indexing to complete..."
sleep "$WAIT_SECS"

STATS_P2_AFTER=$(curl -s "http://$FIRST_WORKER:8082/stats" || echo '{}')
BOOKS_P2_AFTER=$(parse_stat "$STATS_P2_AFTER" "total_books")
WORDS_P2_AFTER=$(parse_stat "$STATS_P2_AFTER" "unique_words")
BOOKS_INDEXED_P2=$((BOOKS_P2_AFTER - BOOKS_P2_BEFORE))
WORDS_INDEXED_P2=$((WORDS_P2_AFTER - WORDS_P2_BEFORE))
TOTAL_P2=$((ELAPSED + WAIT_SECS))
INGEST_RATE_SCALED=$(awk "BEGIN{printf \"%.2f\", $BOOKS_INDEXED_P2 / $TOTAL_P2}" 2>/dev/null || echo "N/A")
INDEX_THROUGHPUT_P2=$(awk "BEGIN{printf \"%.0f\", $WORDS_INDEXED_P2 / $TOTAL_P2}" 2>/dev/null || echo "N/A")

log "  Dispatch: ${ELAPSED}s | Total window (dispatch+wait): ${TOTAL_P2}s"
log "  Books indexed: ${BOOKS_INDEXED_P2} of ${INGEST_COUNT} dispatched | Rate: ${INGEST_RATE_SCALED} docs/s"
log "  Indexing throughput: ${INDEX_THROUGHPUT_P2} tokens/s  (unique_words delta / window)"
log "  Stats after: $STATS_P2_AFTER"
save "$SUMMARY" "" "=== PHASE 2: Scaling ===" \
  "  Nodes: ${#WORKERS[@]} (${WORKERS[*]})" \
  "  Books dispatched: $INGEST_COUNT" \
  "  Books indexed in window: $BOOKS_INDEXED_P2" \
  "  Dispatch elapsed: ${ELAPSED}s  |  Total window: ${TOTAL_P2}s" \
  "  Ingestion rate: ${INGEST_RATE_SCALED} docs/s  (books fully indexed / total window)" \
  "  Indexing throughput: ${INDEX_THROUGHPUT_P2} tokens/s  (unique_words delta / total window)" \
  "  Stats: $STATS_P2_AFTER"

# ──────────────────────────────────────────────────────────────────────────────
# PHASE 3: Load test (concurrent search queries via wrk)
# ──────────────────────────────────────────────────────────────────────────────
hdr "PHASE 3 — Load test (${LOAD_TOOL}, ${WRK_DURATION}s)"
LOAD_FILE="$RESULTS_DIR/phase3_load.txt"
LOAD_URL="http://$MASTER_IP/search?q=${SEARCH_TERM}&limit=10"
log "Target: $LOAD_URL"

if [[ "$LOAD_TOOL" == "wrk" ]]; then
  log "Running wrk — threads=$WRK_THREADS connections=$WRK_CONNECTIONS duration=${WRK_DURATION}s"
  wrk -t "$WRK_THREADS" \
      -c "$WRK_CONNECTIONS" \
      -d "${WRK_DURATION}s" \
      --latency \
      "$LOAD_URL" \
      2>&1 | tee "$LOAD_FILE"

  AVG_LATENCY=$(grep "Latency" "$LOAD_FILE" | grep -v "Distribution" | awk '{print $2}' || echo "N/A")
  P95_LATENCY=$(grep "95%" "$LOAD_FILE" | awk '{print $2}' || echo "N/A")
  P99_LATENCY=$(grep "99%" "$LOAD_FILE" | awk '{print $2}' || echo "N/A")
  MAX_LATENCY=$(grep -E "^\s+Max" "$LOAD_FILE" | awk '{print $NF}' || echo "N/A")
  REQUESTS_SEC=$(grep "Requests/sec" "$LOAD_FILE" | awk '{print $2}' || echo "N/A")

elif [[ "$LOAD_TOOL" == "powershell" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  PS_SCRIPT="$SCRIPT_DIR/load_test.ps1"
  log "Running PowerShell load test — connections=$WRK_CONNECTIONS duration=${WRK_DURATION}s"
  # '|| true' inside the group prevents set -euo pipefail from killing the script
  # when powershell.exe exits non-zero (e.g. a non-fatal RunspacePool warning).
  { "$PS_EXE" -ExecutionPolicy Bypass -File "$PS_SCRIPT" \
      -Url "$LOAD_URL" \
      -Connections "$WRK_CONNECTIONS" \
      -Duration "$WRK_DURATION" \
      2>&1 || true; } | tee "$LOAD_FILE"

  # load_test.ps1 output: "  Avg latency:                   75,11 ms"
  # $NF would be "ms"; $(NF-1) is the numeric value before the unit.
  AVG_LATENCY=$(grep "Avg latency" "$LOAD_FILE" | awk '{print $(NF-1)" ms"}' || echo "N/A")
  P95_LATENCY=$(grep "p95 latency" "$LOAD_FILE" | awk '{print $(NF-1)" ms"}' || echo "N/A")
  P99_LATENCY=$(grep "p99 latency" "$LOAD_FILE" | awk '{print $(NF-1)" ms"}' || echo "N/A")
  MAX_LATENCY=$(grep "Max latency" "$LOAD_FILE" | awk '{print $(NF-1)" ms"}' || echo "N/A")
  REQUESTS_SEC=$(grep "Requests/sec" "$LOAD_FILE" | awk '{print $NF}' || echo "N/A")

else
  # Sequential curl fallback — avg latency only, no concurrency
  log "WARNING: Running sequential curl fallback (100 requests, no concurrency)."
  log "For proper load test results use wrk (Linux) or ensure PowerShell is available."
  TOTAL_MS=0
  COUNT=0
  for i in $(seq 1 100); do
    ms=$(curl -s -o /dev/null -w "%{time_total}" "$LOAD_URL" 2>/dev/null || echo "0")
    ms_int=$(awk "BEGIN{printf \"%.0f\", $ms * 1000}")
    TOTAL_MS=$((TOTAL_MS + ms_int))
    COUNT=$((COUNT + 1))
  done
  AVG_LATENCY="${TOTAL_MS}ms / ${COUNT} = $(awk "BEGIN{printf \"%.1f\", $TOTAL_MS / $COUNT}")ms avg"
  printf 'Curl fallback: %s\n' "$AVG_LATENCY" | tee "$LOAD_FILE"
fi

log "Load test complete. Results saved to $LOAD_FILE"

# Collect docker stats snapshot from master (local only — no SSH available)
log "Collecting docker stats snapshot from this node..."
DOCKER_STATS_FILE="$RESULTS_DIR/phase3_docker_stats.txt"
docker stats --no-stream \
  --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' \
  2>/dev/null | tee "$DOCKER_STATS_FILE" || true
log "NOTE: Run 'docker stats --no-stream' manually on each worker PC and save to benchmarks/results/docker_stats_<hostname>.txt"

CPU_INDEXER=$(awk '/indexer/{gsub(/%/,"",$2); print $2; exit}' "$DOCKER_STATS_FILE" 2>/dev/null || echo "N/A")
CPU_SEARCH=$(awk '/search/{gsub(/%/,"",$2); print $2; exit}' "$DOCKER_STATS_FILE" 2>/dev/null || echo "N/A")
MEM_INDEXER=$(awk '/indexer/{print $3; exit}' "$DOCKER_STATS_FILE" 2>/dev/null || echo "N/A")
MEM_SEARCH=$(awk '/search/{print $3; exit}' "$DOCKER_STATS_FILE" 2>/dev/null || echo "N/A")

save "$SUMMARY" "" "=== PHASE 3: Load test ===" \
  "  Tool: $LOAD_TOOL" \
  "  Target: $LOAD_URL" \
  "  Connections: $WRK_CONNECTIONS | Duration: ${WRK_DURATION}s" \
  "  Avg latency: $AVG_LATENCY" \
  "  p95 latency: $P95_LATENCY" \
  "  p99 latency: $P99_LATENCY" \
  "  Max latency: $MAX_LATENCY" \
  "  Requests/sec: $REQUESTS_SEC" \
  "  CPU indexer: $CPU_INDEXER%  |  CPU search: $CPU_SEARCH%" \
  "  Mem indexer: $MEM_INDEXER  |  Mem search: $MEM_SEARCH" \
  "  Full output: phase3_load.txt"

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
# CSV: append one row per run for Python graphing
# ──────────────────────────────────────────────────────────────────────────────
CSV_HEADER="timestamp,label,nodes,books_dispatched_p1,books_indexed_p1,ingest_rate_p1,tokens_per_s_p1,books_dispatched_p2,books_indexed_p2,ingest_rate_p2,tokens_per_s_p2,query_avg_ms,query_p95_ms,query_p99_ms,query_max_ms,requests_per_sec,cpu_indexer_pct,cpu_search_pct,mem_indexer,mem_search,recovery_s,results_dir"
CSV_ROW="$(date +'%Y-%m-%dT%H:%M:%S'),$CONFIG_LABEL,${#WORKERS[@]},$INGEST_COUNT,$BOOKS_INDEXED_P1,$INGEST_RATE,$INDEX_THROUGHPUT_P1,$INGEST_COUNT,$BOOKS_INDEXED_P2,$INGEST_RATE_SCALED,$INDEX_THROUGHPUT_P2,$AVG_LATENCY,$P95_LATENCY,$P99_LATENCY,$MAX_LATENCY,$REQUESTS_SEC,$CPU_INDEXER,$CPU_SEARCH,$MEM_INDEXER,$MEM_SEARCH,$RECOVERY_TIME,$RESULTS_DIR"
mkdir -p "$(dirname "$CSV_FILE")"
[[ ! -f "$CSV_FILE" ]] && echo "$CSV_HEADER" > "$CSV_FILE"
echo "$CSV_ROW" >> "$CSV_FILE"
log "CSV row appended → $CSV_FILE"

# ──────────────────────────────────────────────────────────────────────────────
# Final summary
# ──────────────────────────────────────────────────────────────────────────────
hdr "Benchmark complete"
log "Results directory: $RESULTS_DIR"
log "Summary:"
cat "$SUMMARY"
