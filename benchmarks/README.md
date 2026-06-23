# Benchmarks — Stage 3

This directory stores all performance results from the Stage 3 benchmark suite.

---

## Full Benchmark Workflow

Run these steps **in order**. Steps 0–1 are done before any load hits the cluster.
Steps 2–5 use the automated scripts.

### Step 0 — Document the lab environment (§4.1)

Run this on **every PC** (including the master). It collects CPU, RAM, link speed,
OS, Java, Docker, and middleware versions into a single text file.

**Windows (Git Bash / PowerShell) — use the PowerShell script:**

```powershell
# Open PowerShell, cd to the stage3\ folder, then:
powershell -ExecutionPolicy Bypass -File scripts\collect_env.ps1
# Output: benchmarks\results\env_<COMPUTERNAME>.txt
```

If PowerShell blocks the script with an execution policy error, run this once as Administrator:
```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

Then copy the file to the master manually (USB, shared folder, or Git commit):

```
Copy: benchmarks\results\env_<COMPUTERNAME>.txt
  → to master PC: benchmarks\results\
```

**Linux / WSL2 only:**
```bash
bash scripts/collect_env.sh
```

---

### Step 1 — Ingest enough data (prerequisite for benchmarks)

The benchmark script needs at least 100 books indexed before Phase 3 (load test).
This script dispatches ingest requests round-robin across all nodes:

```bash
# From the master node, run from stage3/ directory:
bash scripts/lab_ingest_first_n.sh \
  --nodes "PC1_IP PC2_IP PC3_IP PC4_IP PC5_IP PC6_IP" \
  --count 300 \
  --concurrency 30 \
  --async true

# Monitor indexing progress (run on any node):
curl http://PC1_IP:8082/stats
curl http://PC1_IP:8081/index/status
```

Wait until `stats` shows at least 100 indexed books before continuing.

---

### Step 2 — Run the full benchmark suite

The main script runs all four test phases automatically.
The failure test (Phase 4) pauses and asks you to manually stop a container.

**Run from Git Bash on the master PC, from the `stage3/` directory:**

```bash
bash scripts/benchmark.sh \
  --master  <MASTER_IP> \
  --workers "<PC1_IP> <PC2_IP> <PC3_IP> <PC4_IP> <PC5_IP> <PC6_IP>" \
  --count   50 \
  --duration 30 \
  --term    "the"

# Results land in: benchmarks/results/<timestamp>/
```

**How the load test (Phase 3) runs on Windows:**

`benchmark.sh` automatically detects what is available:

| Tool available | What runs | Quality |
|----------------|-----------|---------|
| `wrk` (Linux/WSL2) | `wrk --latency` | Best — p50/p75/p90/p99 |
| `powershell.exe` (Windows) | `load_test.ps1` (runspaces + .NET HttpClient) | Good — p50/p90/p95/p99 |
| Neither | Sequential `curl` loop | Limited — avg latency only |

On Windows with Git Bash, `powershell.exe` is always in PATH, so **load_test.ps1 will run automatically** — you do not need to install anything extra.

| Flag | Default | Meaning |
|------|---------|---------|
| `--master` | required | IP of PC running Nginx + ActiveMQ |
| `--workers` | required | Space-separated IPs of ALL nodes (including master) |
| `--count` | 50 | Books to ingest per phase |
| `--duration` | 30 | `wrk` load test duration in seconds |
| `--term` | `the` | Search term used in load and failure tests |

---

### Step 3 — Capture CPU/memory during Phase 3 (load test)

No SSH between PCs — each person runs this on their own machine
**while Phase 3 of the benchmark script is running**. Open a second PowerShell window:

```powershell
# On each worker PC — run while benchmark.sh Phase 3 is active:
docker stats --no-stream --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}"

# Save to file (replace COMPUTERNAME with actual hostname):
docker stats --no-stream --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}" `
  | Out-File benchmarks\results\docker_stats_$env:COMPUTERNAME.txt
```

Copy all `docker_stats_*.txt` files to `benchmarks\results\` on the master.

---

### Step 4 — Failure test (Phase 4 — manual step)

The benchmark script pauses and prints a message like:

```
Press Enter when you have stopped the search service on <VICTIM_IP>.
```

On the victim node, stop only the search container:

```bash
# On the victim PC:
docker compose --profile node stop search
```

Then press Enter on the master. The script probes Nginx for up to 30 seconds
and records the recovery time.

To restore the node after the test:

```bash
# On the victim PC:
docker compose --profile node start search
```

---

### Step 5 — Repeat for scaling configurations (§4.4)

The guide requires at least **three node counts** (e.g. 1, 3, 6).
Re-run the benchmark script changing `--workers` each time:

```bash
# 1 node (baseline):
bash scripts/benchmark.sh --master <MASTER_IP> --workers "<PC1_IP>" ...

# 3 nodes:
bash scripts/benchmark.sh --master <MASTER_IP> --workers "<PC1_IP> <PC2_IP> <PC3_IP>" ...

# 6 nodes:
bash scripts/benchmark.sh --master <MASTER_IP> --workers "<PC1_IP> <PC2_IP> <PC3_IP> <PC4_IP> <PC5_IP> <PC6_IP>" ...
```

Each run creates its own timestamped folder under `results/`.

---

## Results Directory Structure

```
benchmarks/results/
├── env_<hostname>.txt              # Hardware + software env (one per PC)
├── docker_stats_<hostname>.txt     # CPU/mem snapshot during load test (one per PC)
├── 20260623_100000/                # Timestamped run (1-node baseline)
│   ├── summary.txt                 # Key metrics — all phases
│   ├── phase1_baseline.txt         # Ingestion log (1 node)
│   ├── phase2_scaling.txt          # Ingestion log (N nodes)
│   ├── phase3_load.txt             # wrk output (latency distribution)
│   ├── phase3_docker_stats.txt     # CPU/mem from master node
│   └── phase4_failure.txt          # Failure + recovery probe log
├── 20260623_110000/                # Timestamped run (3-node)
└── 20260623_120000/                # Timestamped run (6-node)
```

---

## Metrics to collect (§4.3)

| Metric | Source | Where to find it |
|--------|--------|-----------------|
| Ingestion rate (docs/s) | `benchmark.sh` | `summary.txt` — Phase 1 & 2 |
| Indexing throughput (tokens/s) | `GET /index/status` | Manual — `curl http://<IP>:8081/index/status` |
| Query latency avg / p99 / max | `wrk --latency` | `phase3_load.txt` |
| CPU / memory per container | `docker stats` | `phase3_docker_stats.txt` + per-node files |
| Recovery time (s) | `benchmark.sh` | `summary.txt` — Phase 4 |

### Collecting indexing throughput manually

`wrk` measures search latency; indexing throughput must be sampled by hand:

```bash
# Poll index status before and after a timed ingest window:
curl http://<NODE_IP>:8081/index/status   # record "indexedCount" at T0
# wait 30 seconds while ingestion runs
curl http://<NODE_IP>:8081/index/status   # record "indexedCount" at T1
# tokens/s ≈ (T1.indexedCount - T0.indexedCount) × avg_tokens_per_doc / 30
```

---

## Results table (fill in from summary.txt after each run)

| # Nodes | Ingestion rate (docs/s) | Query latency avg (ms) | Query latency p99 (ms) | CPU % (avg per node) | Recovery (s) |
|---------|-------------------------|------------------------|------------------------|----------------------|--------------|
| 1       |                         |                        |                        |                      | N/A          |
| 3       |                         |                        |                        |                      |              |
| 6       |                         |                        |                        |                      |              |

---

## Lab hardware (fill in from env_*.txt after running collect_env.sh)

| Node | Role | CPU | Cores | RAM | Link speed |
|------|------|-----|-------|-----|------------|
| PC1  | master + node | | | | |
| PC2  | node | | | | |
| PC3  | node | | | | |
| PC4  | node | | | | |
| PC5  | node | | | | |
| PC6  | node | | | | |

---

## Software versions (fill in from env_*.txt)

| Software | Version |
|----------|---------|
| OS | |
| Java | |
| Docker | |
| Docker Compose | |
| Spring Boot | |
| Hazelcast | |
| ActiveMQ image | |
| Nginx image | |

---

## Notes on Gutenberg dataset

Project Gutenberg IDs are sparse — ~30% of IDs return 404 or are not available
in the requested format. This is normal. The benchmark counts **successful**
downloads only, so report throughput based on HTTP 200/201/202 responses,
which are logged individually by `lab_ingest_first_n.sh`.

---

## wrk output format — quick reference

`wrk --latency` reports percentile buckets like this:

```
Latency Distribution
   50%    8.23ms
   75%   12.10ms
   90%   18.44ms
   99%   45.21ms
  Avg    9.17ms
  Max   234.56ms
Requests/sec:   512.34
```

The project guide asks for **avg, p95, and max**. `wrk` does not output p95 directly;
use the p99 bucket as a conservative upper bound and note it in the report.
