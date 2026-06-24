# Benchmark Guide -- Stage 3

**6 Windows PCs · Git Bash · Docker Desktop**

PC1 = master (ActiveMQ + Nginx) + node
PC2-PC6 = node only

All bash commands: **PC1, Git Bash, from the `stage3/` folder**
All PowerShell commands: **each individual PC, PowerShell, from the `stage3\` folder**

---

## Step 0 -- Collect environment info (ONE TIME, every PC)

Open PowerShell on each PC, navigate to `stage3\`, run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\collect_env.ps1
```

Output: `benchmarks\results\env_<COMPUTERNAME>.txt`
Copy file to PC1 into `benchmarks\results\`.

---

## Step 1 -- Verify the cluster before starting

On PC1, Git Bash:

```bash
curl http://PC1_IP:8080/health   # crawler OK
curl http://PC1_IP:8081/health   # indexer OK
curl http://PC1_IP:8082/health   # search  OK
curl http://PC1_IP/              # Nginx   OK
curl http://PC1_IP:8082/stats    # shows books already indexed
```

---

## Step 2 -- Configuration A: 1 node

**Stop the node services on PC2, PC3, PC4, PC5, PC6.**
Run this command on each of those PCs (Git Bash or PowerShell):

```bash
docker compose --profile node stop
```

Wait 20 seconds. Verify on PC1 that only PC1 responds:

```bash
curl http://PC2_IP:8082/health   # should time out or fail
curl http://PC1_IP:8082/health   # should still be OK
```

### IMPORTANT: 1-node ingestion requires setting REPLICATION_FACTOR=1

The system is designed with R=2 (each book is replicated to a peer node).
With all other nodes stopped, the crawler has NO replication targets and returns
HTTP 500 for every ingest request. This means Phases 1 and 2 will show 0
successful books and "N/A" ingestion rate unless you lower the replication
factor first.

**Before running Config A, on PC1:**

1. Open `.env` (in the `stage3/` folder) and change:
   ```
   REPLICATION_FACTOR=2
   ```
   to:
   ```
   REPLICATION_FACTOR=1
   ```

2. Restart the crawler so it picks up the change:
   ```bash
   docker compose --profile master restart crawler
   # or if it is named differently:
   docker compose --profile node restart crawler
   ```

3. Confirm the change took effect:
   ```bash
   curl http://PC1_IP:8080/health
   ```

After Config A is done, set `REPLICATION_FACTOR=2` back and restart the
crawler before running Config B and C. With 3 and 6 nodes there are always
enough peers for replication to succeed normally.

### Run the benchmark on PC1, Git Bash

```bash
bash scripts/benchmark.sh \
  --master        PC1_IP \
  --workers       "PC1_IP" \
  --ingest-start  301 \
  --count         50 \
  --duration      30 \
  --term          "the" \
  --label         1node
```

What happens inside:
- Phase 1: ingests books 301-350 using PC1 only -- measures 1-node ingestion rate
- Phase 2: ingests books 351-400 using PC1 only (same result; confirms rate is stable)
- Phase 3: load test against Nginx (50 concurrent connections, 30 s)
- Phase 4: failure test -- the script will ask you to stop a container

For Phase 4 with only 1 node there is nothing to fail over to, so skip it:
when the script asks you to stop a service, press Enter immediately without
stopping anything. The script will record "did not recover" which is correct
for a 1-node setup.

---

## Step 3 -- Configuration B: 3 nodes

**Start the node services on PC2 and PC3.**
Run on PC2 and on PC3 (Git Bash or PowerShell):

```bash
docker compose --profile node up -d
```

Wait 20 seconds for Hazelcast to detect the new members. Check on PC1:

```bash
curl http://PC1_IP:8081/index/status
# look for "clusterSize": 3
```

**Run the benchmark on PC1, Git Bash:**

```bash
bash scripts/benchmark.sh \
  --master        PC1_IP \
  --workers       "PC1_IP PC2_IP PC3_IP" \
  --ingest-start  401 \
  --count         50 \
  --duration      30 \
  --term          "the" \
  --label         3node
```

What happens inside:
- Phase 1: ingests books 401-450 using PC1 only -- 1-node rate inside a 3-node cluster
- Phase 2: ingests books 451-500 spread across PC1+PC2+PC3 -- 3-node rate
- Phase 3: load test, Nginx routes across 3 search nodes
- Phase 4: failure test -- when prompted, stop the search service on PC2 or PC3:

```bash
# On the victim PC (PC2 or PC3):
docker compose --profile node stop search   # partial: test Nginx failover
# OR
docker compose --profile node stop          # full: test Hazelcast recovery too
```

Press Enter on PC1. After the test, restore the victim:

```bash
docker compose --profile node up -d
```

---

## Step 4 -- Configuration C: 6 nodes

**Start the node services on PC4, PC5, PC6.**
Run this on PC4, PC5, and PC6:

```bash
docker compose --profile node up -d
```

Wait 20 seconds. Check for 6 members:

```bash
curl http://PC1_IP:8081/index/status
```

**Run the benchmark on PC1, Git Bash:**

```bash
bash scripts/benchmark.sh \
  --master        PC1_IP \
  --workers       "PC1_IP PC2_IP PC3_IP PC4_IP PC5_IP PC6_IP" \
  --ingest-start  501 \
  --count         50 \
  --duration      30 \
  --term          "the" \
  --label         6node
```

What happens inside:
- Phase 1: ingests books 501-550 using PC1 only -- 1-node rate inside a 6-node cluster
- Phase 2: ingests books 551-600 spread across all 6 nodes -- 6-node rate
- Phase 3: load test, Nginx routes across 6 search nodes
- Phase 4: failure test -- stop any worker PC except PC1 (PC1 has Nginx and ActiveMQ):

```bash
# On the victim PC (PC2 through PC6):
docker compose --profile node stop   # full node failure
```

Restore after:

```bash
docker compose --profile node up -d
```

---

## Step 5 -- Collect CPU/memory during each load test (Phase 3)

**WHEN:** While the benchmark script is running Phase 3 (load test) — you will see
`"Running... Xs left"` in the terminal. You have 30 seconds. Don't wait until Phase 3 finishes.

**WHO:** Every PC that has a Docker container running (all 6 PCs for Config C,
first 3 for Config B, only PC1 for Config A).

**HOW:** Open a **second PowerShell window on each PC** and run:

```powershell
docker stats --no-stream `
  --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}"
```

Save to a file (do this on every PC):

```powershell
docker stats --no-stream `
  --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}" `
  | Out-File "benchmarks\results\docker_stats_$env:COMPUTERNAME.txt"
```

After each run, collect all files on PC1:
1. Each person copies their `docker_stats_<COMPUTERNAME>.txt` to a USB or shared folder.
2. PC1 collects them all into `benchmarks\results\` (alongside the run results directory).

> The benchmark script already captures docker stats for PC1 automatically
> (`phase3_docker_stats.txt` inside the run directory). The manual step is for
> the remaining nodes (PC2–PC6) which are not accessible via SSH.

---

## Where results are

```
benchmarks/results/
|-- env_<COMPUTERNAME>.txt              one per PC (Step 0)
|-- docker_stats_<COMPUTERNAME>.txt     one per PC per run (Step 5)
|
|-- 20260623_100000/     Config A (1 node)
|   |-- summary.txt      <-- open this: all metrics in one place
|   |-- phase1_baseline.txt
|   |-- phase2_scaling.txt
|   |-- phase3_load.txt
|   |-- phase3_docker_stats.txt
|   `-- phase4_failure.txt
|
|-- 20260623_110000/     Config B (3 nodes)
`-- 20260623_120000/     Config C (6 nodes)
```

---

## Health checks

```bash
curl http://IP:8080/health          # crawler
curl http://IP:8081/health          # indexer
curl http://IP:8082/health          # search
curl http://IP:8082/stats           # index stats (book count, term count)
curl http://IP:8081/index/status    # hazelcast cluster info
curl http://PC1_IP/                 # nginx load balancer
# ActiveMQ web console (browser): http://PC1_IP:8161  login: admin / admin
```