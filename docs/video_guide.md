# Stage 3 — Demonstration Video Guide (Lab Hardware)

Step-by-step runbook to record the required Stage 3 demo video in the laboratory.

Target duration: **4–7 minutes** (max 10).

Video title (required — must match exactly):
```
[Stage 3] Search Engine Project - <Group Name> (ULPGC)
```

Upload to YouTube as **Unlisted** (not Private), and paste the link in `README.md`.

---

## 0) Rubric checklist (§5.3 of the PDF)

Your video must show **all five** of these — the guide is structured to hit them in order:

| # | Requirement | Where in this guide |
|---|-------------|---------------------|
| 1 | Deployment with Docker | §3 — 0:20–1:00 |
| 2 | Real-time ingestion + indexing + search across multiple nodes | §3 — 1:00–1:45 |
| 3 | Load test demonstrating horizontal scalability | §3 — 1:45–3:30 |
| 4 | Simulated node failure + automatic recovery | §3 — 3:30–5:00 |
| 5 | Logs/monitoring showing throughput, latency, resource utilization | §3 — 5:00–6:30 |

---

## 1) Per-PC responsibilities

Coordinate this with your group BEFORE arriving at the lab. Everyone must know their role.

| PC | Role | Pre-video | During load test (§3 — 1:45–3:30) | During failure test (§3 — 3:30–5:00) |
|----|------|-----------|-------------------------------------|---------------------------------------|
| **PC1 (Master)** | Recording + orchestration | Start `--profile master --profile node`. Open OBS. | Run benchmark script from Terminal A. Show ActiveMQ UI in browser. | Run `curl -i` loop. Watch `docker logs indexer`. |
| **PC2** | Worker + failure victim | Start `--profile node`. | Open a second terminal: `docker stats --no-stream`. Show to camera briefly. | **Stop search service** on command from PC1. Then restart it. |
| **PC3** | Worker | Start `--profile node`. | Standby. | Standby. |
| **PC4** | Worker (6-node only) | Start `--profile node` only for the 6-node run. | Standby. | Standby. |
| **PC5** | Worker (6-node only) | Start `--profile node` only for the 6-node run. | Standby. | Standby. |
| **PC6** | Worker (6-node only) | Start `--profile node` only for the 6-node run. | Standby. | Standby. |

> **PC1 drives everything visible in the video.** Other PCs just keep their containers running and
> respond to their single action (PC2: docker stats snapshot + failure). The camera stays on PC1
> except for a brief cut to PC2's docker stats screen.

---

## 2) Recording setup

### Where to record
Record from **PC1** (master). OBS captures PC1's screen and microphone the whole time.

### Windows layout on PC1
Keep these open and ready to switch between:

- **Terminal A (Git Bash)**: benchmark script + main commands
- **Terminal B (Git Bash)**: monitoring loop (`while true; do ...`)
- **PowerShell window**: for the load test (`load_test.ps1`)
- **Browser tab**: ActiveMQ UI `http://<PC1_IP>:8161` (admin/admin)
- **Browser tab (optional)**: `http://<PC1_IP>/search?q=the&limit=5` via Nginx

### Preparation checklist (do this BEFORE pressing Record)
- [ ] All 6 PCs have containers running (`docker ps` shows crawler, indexer, search)
- [ ] PC1 also shows activemq + nginx running
- [ ] `curl -s http://<PC1_IP>:8082/stats` returns JSON with books indexed (not zero)
- [ ] `curl -s http://<PC1_IP>/search?q=the&limit=5` returns results via Nginx
- [ ] OBS is capturing the right screen + microphone is live
- [ ] Browser tabs are open and not logged out of ActiveMQ

---

## 3) Video script (time-coded) — follow this exactly

Replace `<M>` with PC1's IP, `<W1>` with PC2's IP, `<W2>` with PC3's IP, etc.

---

### 0:00–0:20 — Intro

On **PC1**, Terminal A:

```bash
hostname
ipconfig | findstr /i "IPv4"
```

**Say:**
> "We are running Stage 3 on official lab hardware — 6 physical PCs connected on the same network.
> This demo covers deployment, distributed ingestion and indexing, load testing with horizontal
> scalability, node failure and automatic recovery, and live monitoring."

---

### 0:20–1:00 — Deployment with Docker (rubric #1)

**PC1, Terminal A** — start master services (ActiveMQ + Nginx) and the local worker:

```bash
cd /path/to/stage3
docker compose --profile master --profile node up -d
docker ps
```

Show the output: activemq, nginx, crawler, indexer, search all running.

**Then narrate while switching to browser:**
> "ActiveMQ is our message broker. Nginx is our load balancer. PC1 also runs crawler, indexer,
> and search — it is both the master and a worker node."

Open browser → `http://<M>:8161` → show the ActiveMQ web console briefly (Queues tab).

**Coordinate (off-camera or say aloud):**
> "PC2 through PC6 each run `docker compose --profile node up -d` — they are already up."

To prove them: on **PC1**, Terminal A:

```bash
curl -s http://<W1>:8082/health
curl -s http://<W2>:8082/health
```

Both return healthy. **Say:**
> "All 6 search nodes are live and reachable."

---

### 1:00–1:45 — Real-time ingestion → indexing → search (rubric #2)

**PC1, Terminal B** — open a live stats monitor:

```bash
while true; do
  clear
  echo "=== Cluster index stats ==="
  echo "PC1:"; curl -s http://<M>:8082/stats
  echo ""
  echo "PC2:"; curl -s http://<W1>:8082/stats
  sleep 2
done
```

Leave this running in Terminal B (visible on screen).

**PC1, Terminal A** — trigger ingestion of one book:

```bash
curl -i -X POST "http://<M>:8080/ingest/100?async=true"
```

Shows HTTP 201. **Say:**
> "201 means accepted — the crawler downloads the Gutenberg book asynchronously, stores it in the
> datalake, and publishes an ActiveMQ event."

Watch Terminal B — within ~10 seconds the `unique_words` count increases in PC1's stats.

**PC1, Terminal A** — show search results via Nginx (load balancer):

```bash
curl -i "http://<M>/search?q=great&limit=5"
```

Point out the `X-Upstream-Addr` header — it shows which backend PC served the request. **Say:**
> "Nginx routed this query to one of the search nodes. The Hazelcast in-memory inverted index
> returns results from the shared distributed index — all nodes have access to all indexed data."

Run it twice to show it hitting different nodes:

```bash
curl -s -I "http://<M>/search?q=great&limit=5" | grep -i upstream
curl -s -I "http://<M>/search?q=great&limit=5" | grep -i upstream
```

---

### 1:45–3:30 — Load test + horizontal scalability (rubric #3)

This is the most important section. You will run a live search load test and then show the
pre-run results comparing 1-node, 3-node, and 6-node configurations.

**PC1, Terminal A** — kill the monitoring loop in Terminal B first (Ctrl+C), then run the load test:

```bash
powershell.exe -ExecutionPolicy Bypass -File scripts/load_test.ps1 \
  -Url "http://<M>/search?q=the&limit=10" \
  -Connections 50 \
  -Duration 30
```

> The test runs 50 concurrent connections for 30 seconds. Let it run — do not cut.

While the test runs, **say:**
> "We are sending 50 concurrent search requests to Nginx. Each request is distributed via
> least-connections to one of the 6 search nodes. The Hazelcast cluster distributes query results
> across nodes and merges them before responding."

After ~33 seconds the results appear. **Point to and read aloud:**
- Avg latency: ~39 ms
- p95 latency: ~66 ms
- p99 latency: ~96 ms
- Requests/sec: ~1128

**Say:**
> "1128 requests per second at 39 ms average with 6 nodes. Now compare with 1 node."

**PC1, Terminal A** — show the pre-run benchmark comparison:

```bash
grep -A 6 "PHASE 3" benchmarks/results/1node_*/summary.txt
grep -A 6 "PHASE 3" benchmarks/results/6node_*/summary.txt
```

Or open the summary files side by side:

```bash
cat benchmarks/results/1node_*/summary.txt | grep -E "Requests/sec|Avg latency|p99"
cat benchmarks/results/6node_*/summary.txt | grep -E "Requests/sec|Avg latency|p99"
```

**Say:**
> "1 node: 591 req/s at 75 ms average. 6 nodes: 1128 req/s at 39 ms average. That is
> 1.9× throughput improvement and 48% latency reduction — horizontal scaling is working."

---

### 3:30–5:00 — Simulated node failure + recovery (rubric #4)

**Say:**
> "We will now stop the search service on PC2 while the cluster is serving requests. This
> simulates a node crash. We want to verify two things: Nginx automatically reroutes traffic,
> and Hazelcast redistributes the index partitions to surviving nodes."

**PC1, Terminal B** — start a continuous search probe (measures recovery time):

```bash
i=1
while true; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://<M>/search?q=the&limit=5")
  echo "$(date +%H:%M:%S) attempt $i — HTTP $STATUS"
  i=$((i+1))
  sleep 1
done
```

**PC2** — stop ONLY the search service (not the whole node):

```bash
docker compose --profile node stop search
```

> All requests must continue to succeed (HTTP 200) in Terminal B on PC1 — just slower for
> 1-2 seconds while Nginx detects the failure via health check. **Say:**
> "Nginx detected the failure and rerouted to the remaining 5 nodes. No request was lost."

**PC1, Terminal A** — show Hazelcast partition migration logs:

```bash
docker logs stage3-indexer-1 --tail 50 2>&1 | grep -iE "member|partition|left|join|migrat"
```

You should see lines like `Members [5]` (down from 6) and partition reassignment. **Say:**
> "Hazelcast detected the missing member and automatically rebalanced index partitions across
> the remaining nodes. Replica promotion ensures no data loss."

**Confirm search still returns correct results:**

```bash
curl -s "http://<M>/search?q=the&limit=3"
```

**PC2** — restart the search service:

```bash
docker compose --profile node up -d search
```

**PC1, Terminal A** — wait 5 seconds, then confirm it rejoined:

```bash
docker logs stage3-indexer-1 --tail 20 2>&1 | grep -iE "member|join"
curl -s http://<W1>:8082/health
```

**Say:**
> "The node is back. Hazelcast rebalanced partitions again. Recovery took approximately 2–3
> seconds — the time for Nginx's health check to detect availability."

Stop the probe loop (Ctrl+C in Terminal B).

---

### 5:00–6:30 — Monitoring: logs, dashboard, resource utilization (rubric #5)

**Three things to show — do all three:**

**① ActiveMQ dashboard — PC1, switch to browser:**

Open `http://<M>:8161` → Queues tab. Point out:
- The `document.ingested` queue
- Number of messages enqueued vs. dequeued (should be near equal — all processed)

**Say:**
> "ActiveMQ shows all ingestion events were consumed by the indexers. At-least-once delivery
> ensures no document is lost even under transient failures."

**② docker stats from PC1 AND PC2 — show resource utilization across nodes:**

**PC1, Terminal A:**

```bash
docker stats --no-stream \
  --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}"
```

Point to indexer CPU (~26%) and search CPU (~16%) and their memory usage.

> "PC2 — please show your docker stats." ← PC2 runs the same command and the camera briefly
> shows their screen (or they share it).

**Say:**
> "CPU is distributed across nodes. The indexer is the most CPU-intensive service — it processes
> ActiveMQ events and updates the Hazelcast MultiMap. Memory stays well within limits."

**③ Benchmark summary — final evidence:**

**PC1, Terminal A:**

```bash
cat benchmarks/results/6node_*/summary.txt
```

Scroll through to show all phases: ingestion rate, indexing throughput, query latency, recovery
time. **Say:**
> "All metrics are recorded and reproducible. The benchmark script is in the repository — anyone
> can reproduce these results by running it against the same cluster configuration."

**Wrap up (say):**
> "Stage 3 demonstrated a fault-tolerant, horizontally scalable distributed search engine: Docker
> deployment across 6 nodes, distributed indexing via Hazelcast and ActiveMQ, 1128 req/s under
> load with sub-40 ms average latency, automatic failure recovery in under 3 seconds, and
> real-time monitoring. Thank you."

---

## 4) After the recording

1. Upload to YouTube as **Unlisted**.
2. Title must be: `[Stage 3] Search Engine Project - <Group Name> (ULPGC)`
3. Add the link to `README.md`:

```md
## Demo Video

[Stage 3] Search Engine Project - <Group Name> (ULPGC)
https://youtu.be/<your-id>
```

---

## 5) Common pitfalls (avoid these)

| Problem | Fix |
|---------|-----|
| `X-Upstream-Addr` not in response headers | Verify Nginx config has `proxy_set_header X-Upstream-Addr $upstream_addr;` in the location block |
| Load test shows 0 req/s or errors | Verify Nginx is up: `curl -i http://<M>/search?q=test` before running the PS script |
| Hazelcast logs show nothing | Use `docker logs stage3-indexer-1 2>&1 \| grep -i member` — the `2>&1` is needed because Hazelcast logs to stderr |
| PC2 search stops, but HTTP probe still shows 200 | That is correct — Nginx rerouted. Wait 2–3 seconds and it stabilises |
| Ingestion stats don't update in 10 seconds | Gutenberg download can take 20–30 s. Keep the loop running and narrate what is happening |
| `docker compose` not found on a worker PC | Run from the directory where `docker-compose.yml` is: `cd /c/Users/Examen/...` |
| Starting only `--profile master` on PC1 | Must use both: `docker compose --profile master --profile node up -d` |
| Failure demo: no visible difference | Make sure the probe loop in Terminal B is running BEFORE stopping PC2's search service |

---

## 6) Coordination script (what to say to your group in the lab)

Print this and hand it out:

```
BEFORE RECORDING:
  ALL PCs: cd to repo folder, run: docker compose --profile node up -d
  PC1 only: docker compose --profile master --profile node up -d
  ALL PCs: confirm docker ps shows crawler, indexer, search
  PC1: open OBS, open browser to ActiveMQ UI, open 2 Git Bash terminals

DURING LOAD TEST (~1:45 in video):
  PC2: stand by — when PC1 says "docker stats", you run:
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}"
  Show your screen to the camera for 5 seconds.

DURING FAILURE TEST (~3:30 in video):
  PC2: when PC1 says "stopping PC2 search service now", you run:
    docker compose --profile node stop search
  Wait ~30 seconds. When PC1 says "restarting", you run:
    docker compose --profile node up -d search
```
