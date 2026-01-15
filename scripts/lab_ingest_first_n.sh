#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/lab_ingest_first_n.sh --nodes "IP1 IP2 IP3" [--count 1000] [--start 1] [--concurrency 20] [--async true]

What it does:
  - Sends POST /ingest/{bookId} requests in round-robin across the given worker nodes.
  - Uses xargs parallelism for concurrency.

Notes:
  - Expects each node to run ingestion-service (crawler) on port 8080.
  - Gutenberg IDs are sparse; failures/timeouts are normal when you hit missing IDs.

Examples:
  scripts/lab_ingest_first_n.sh --nodes "10.26.14.200 10.26.14.207 10.26.14.210" --count 1000 --concurrency 30
EOF
}

NODES_STR=""
COUNT=1000
START=1
CONCURRENCY=20
ASYNC=true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --nodes)
      NODES_STR="${2:-}"; shift 2 ;;
    --count)
      COUNT="${2:-}"; shift 2 ;;
    --start)
      START="${2:-}"; shift 2 ;;
    --concurrency)
      CONCURRENCY="${2:-}"; shift 2 ;;
    --async)
      ASYNC="${2:-}"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$NODES_STR" ]]; then
  echo "Missing --nodes" >&2
  usage
  exit 2
fi

read -r -a NODES <<<"$NODES_STR"
if [[ ${#NODES[@]} -lt 1 ]]; then
  echo "--nodes must include at least one node IP" >&2
  exit 2
fi

if ! [[ "$COUNT" =~ ^[0-9]+$ ]] || [[ "$COUNT" -le 0 ]]; then
  echo "--count must be a positive integer" >&2
  exit 2
fi
if ! [[ "$START" =~ ^[0-9]+$ ]] || [[ "$START" -le 0 ]]; then
  echo "--start must be a positive integer" >&2
  exit 2
fi
if ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] || [[ "$CONCURRENCY" -le 0 ]]; then
  echo "--concurrency must be a positive integer" >&2
  exit 2
fi

log() {
  printf '%s %s\n' "$(date +'%H:%M:%S')" "$*";
}

request_one() {
  local node="$1"
  local book_id="$2"

  # Keep it robust under load:
  # - fail on connection issues (so you see it)
  # - short connect timeout to avoid stalls
  # - modest overall timeout
  local url="http://${node}:8080/ingest/${book_id}?async=${ASYNC}"
  local http_code
  http_code=$(curl -sS -o /dev/null -w "%{http_code}" \
    --connect-timeout 2 \
    --max-time 30 \
    -X POST "$url" || echo "000")

  log "node=${node} book=${book_id} http=${http_code}"
}

export -f request_one
export -f log

log "Dispatching COUNT=${COUNT} START=${START} across ${#NODES[@]} nodes (concurrency=${CONCURRENCY}, async=${ASYNC})"

# Generate (node, book_id) pairs in round-robin and fire them concurrently.
{
  for ((k=0; k<COUNT; k++)); do
    book_id=$((START + k))
    node_index=$((k % ${#NODES[@]}))
    printf '%s %s\n' "${NODES[$node_index]}" "$book_id"
  done
} | xargs -n 2 -P "$CONCURRENCY" bash -c 'request_one "$0" "$1"' 

log "Done dispatching. Monitoring tips:"
log "- Per node downloaded count: curl -s http://<NODE>:8080/ingest/list"
log "- Per node indexing status:  curl -s http://<NODE>:8081/index/status"
log "- Per node search stats:     curl -s http://<NODE>:8082/stats"
log "- Nginx backend selection:   curl -i http://<MASTER_IP>/search?q=the | grep X-Upstream-Addr"
