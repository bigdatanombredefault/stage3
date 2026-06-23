#!/usr/bin/env bash
# collect_env.sh — Collect hardware and software environment for §4.1 (Experimental Setup)
# of the Stage 3 project guide.
#
# ⚠  WINDOWS USERS: this script requires Linux tools (lscpu, free, ip, /sys paths).
#    Run the PowerShell version instead:
#      powershell -ExecutionPolicy Bypass -File scripts/collect_env.ps1
#
# Run on EACH lab PC (Linux / WSL2) before benchmarking:
#   bash scripts/collect_env.sh
#
# Output: benchmarks/results/env_<hostname>.txt

set -euo pipefail

if [[ "$(uname -s)" == *"MINGW"* || "$(uname -s)" == *"MSYS"* || "$(uname -s)" == *"CYGWIN"* ]]; then
  echo "ERROR: This script does not work on Git Bash / Windows." >&2
  echo "Run the PowerShell version instead:" >&2
  echo "  powershell -ExecutionPolicy Bypass -File scripts/collect_env.ps1" >&2
  exit 1
fi

RESULTS_DIR="benchmarks/results"
mkdir -p "$RESULTS_DIR"

HOST=$(hostname)
OUT="$RESULTS_DIR/env_${HOST}.txt"

sep() { printf '%0.s-' {1..50}; printf '\n'; }
hdr() { printf '\n### %s\n' "$*"; sep; }

{
  printf '# Lab Environment — Node: %s\n' "$HOST"
  printf 'Collected: %s\n' "$(date)"

  # ── HARDWARE ─────────────────────────────────────────────────────────────
  hdr "HARDWARE"

  printf 'Hostname      : %s\n' "$HOST"

  CPU_MODEL=$(lscpu 2>/dev/null \
    | grep -m1 'Model name' \
    | awk -F: '{gsub(/^[ \t]+/,"",$2); print $2}' \
    || echo "N/A")
  CPU_CORES=$(nproc 2>/dev/null || echo "N/A")
  CPU_THREADS=$(lscpu 2>/dev/null | awk '/^CPU\(s\):/{print $2}' || echo "N/A")

  printf 'CPU model     : %s\n' "$CPU_MODEL"
  printf 'Physical cores: %s\n' "$CPU_CORES"
  printf 'Logical threads: %s\n' "$CPU_THREADS"

  RAM_TOTAL=$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo "N/A")
  printf 'RAM total     : %s\n' "$RAM_TOTAL"

  DISK_INFO=$(df -h --total 2>/dev/null \
    | awk '/^total/{print $2 " total, " $4 " free"}' \
    || echo "N/A")
  printf 'Disk          : %s\n' "$DISK_INFO"

  # Primary non-loopback interface
  PRIMARY_IFACE=$(ip route get 1.1.1.1 2>/dev/null \
    | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}' \
    || echo "")

  if [[ -n "$PRIMARY_IFACE" ]]; then
    IP_ADDR=$(ip -4 addr show "$PRIMARY_IFACE" 2>/dev/null \
      | awk '/inet /{print $2}' \
      || echo "N/A")
    LINK_MBPS=$(cat "/sys/class/net/${PRIMARY_IFACE}/speed" 2>/dev/null \
      || echo "N/A — run: ethtool $PRIMARY_IFACE | grep Speed")
    printf 'Network iface : %s  IP: %s\n' "$PRIMARY_IFACE" "$IP_ADDR"
    printf 'Link speed    : %s Mbps\n' "$LINK_MBPS"
  else
    printf 'Network iface : could not detect — run: ip addr\n'
    printf 'Link speed    : run: cat /sys/class/net/<iface>/speed\n'
  fi

  # ── OS / KERNEL ───────────────────────────────────────────────────────────
  hdr "OPERATING SYSTEM"

  OS_PRETTY=$(grep -m1 'PRETTY_NAME' /etc/os-release 2>/dev/null \
    | cut -d'"' -f2 \
    || echo "$(uname -s)")
  KERNEL=$(uname -r)
  ARCH=$(uname -m)

  printf 'OS            : %s\n' "$OS_PRETTY"
  printf 'Kernel        : %s\n' "$KERNEL"
  printf 'Architecture  : %s\n' "$ARCH"

  # ── RUNTIME VERSIONS ─────────────────────────────────────────────────────
  hdr "RUNTIME VERSIONS"

  JAVA_VER=$(java -version 2>&1 | head -1 || echo "not found — is Java on PATH?")
  printf 'Java          : %s\n' "$JAVA_VER"

  DOCKER_VER=$(docker --version 2>/dev/null || echo "not found")
  printf 'Docker        : %s\n' "$DOCKER_VER"

  DC_VER=$(docker compose version 2>/dev/null || echo "not found")
  printf 'Docker Compose: %s\n' "$DC_VER"

  # ── RUNNING CONTAINERS ───────────────────────────────────────────────────
  hdr "RUNNING CONTAINERS"

  if docker ps &>/dev/null 2>&1; then
    docker ps --format "  {{.Names}}\t{{.Image}}\t{{.Status}}" 2>/dev/null \
      || echo "  (none)"
  else
    printf '  docker ps failed — add your user to the docker group or run with sudo\n'
    printf '  Command to check: sudo docker ps\n'
  fi

  # ── MIDDLEWARE VERSIONS ───────────────────────────────────────────────────
  hdr "MIDDLEWARE VERSIONS"

  # Hazelcast — try pom.xml first, then container logs
  HZ_VER="unknown"
  for pom in "indexing-service/pom.xml" "search-service/pom.xml" "pom.xml"; do
    if [[ -f "$pom" ]]; then
      v=$(grep -A1 'hazelcast' "$pom" 2>/dev/null \
        | grep '<version>' \
        | head -1 \
        | tr -d ' \t<>/version' \
        || true)
      if [[ -n "$v" ]]; then
        HZ_VER="$v  (from $pom)"
        break
      fi
    fi
  done
  if [[ "$HZ_VER" == "unknown" ]]; then
    # Try reading from a running indexer container
    INDEXER_CTR=$(docker ps --format '{{.Names}}' 2>/dev/null \
      | grep -i 'index' | head -1 || true)
    if [[ -n "$INDEXER_CTR" ]]; then
      hz_log=$(docker logs "$INDEXER_CTR" 2>&1 \
        | grep -i 'hazelcast' \
        | grep -i 'version\|starting\|[0-9]\+\.[0-9]' \
        | head -1 \
        || true)
      [[ -n "$hz_log" ]] && HZ_VER="$hz_log  (from container logs)"
    fi
  fi
  printf 'Hazelcast     : %s\n' "$HZ_VER"

  # ActiveMQ — from running container image tag
  AMQ_VER="not running on this node"
  AMQ_CTR=$(docker ps --format '{{.Names}}' 2>/dev/null \
    | grep -i 'activemq\|broker' | head -1 || true)
  if [[ -n "$AMQ_CTR" ]]; then
    AMQ_IMG=$(docker inspect "$AMQ_CTR" \
      --format '{{.Config.Image}}' 2>/dev/null || echo "N/A")
    AMQ_VER="$AMQ_IMG  (container: $AMQ_CTR)"
  fi
  printf 'ActiveMQ      : %s\n' "$AMQ_VER"

  # Nginx — from running container
  NGINX_VER="not running on this node"
  NGINX_CTR=$(docker ps --format '{{.Names}}' 2>/dev/null \
    | grep -i 'nginx\|load.balancer\|lb' | head -1 || true)
  if [[ -n "$NGINX_CTR" ]]; then
    NGINX_VER=$(docker exec "$NGINX_CTR" nginx -v 2>&1 \
      | head -1 \
      || echo "check container: docker exec $NGINX_CTR nginx -v")
  fi
  printf 'Nginx         : %s\n' "$NGINX_VER"

  # ── CLUSTER CONFIG ────────────────────────────────────────────────────────
  hdr "CLUSTER CONFIGURATION"

  # Read from .env if present
  ENV_FILE=".env"
  if [[ -f "$ENV_FILE" ]]; then
    printf 'Values from .env:\n'
    grep -E 'CURRENT_NODE_IP|MASTER_NODE_IP|CLUSTER_NODES|REPLICATION|BACKUP' "$ENV_FILE" 2>/dev/null \
      | sed 's/^/  /' \
      || printf '  (no matching keys found)\n'
  else
    printf '.env not found — run from the stage3/ directory\n'
    printf 'Replication factor  : check REPLICATION_FACTOR in .env (expected: 2)\n'
    printf 'Hazelcast backups   : backupCount=2, asyncBackupCount=1  (see hazelcast.xml)\n'
  fi

  # ── DOCKER STATS SNAPSHOT ─────────────────────────────────────────────────
  hdr "DOCKER STATS SNAPSHOT (idle)"
  printf '(Run this DURING the load test too — see benchmarks/README.md)\n\n'
  docker stats --no-stream --format \
    'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' 2>/dev/null \
    || printf '  docker stats unavailable\n'

} | tee "$OUT"

printf '\n'
printf 'Saved → %s\n' "$OUT"
printf 'Transfer this file to the master node:\n'
printf '  scp %s <master-ip>:<path-to-stage3>/%s\n' "$OUT" "$OUT"
