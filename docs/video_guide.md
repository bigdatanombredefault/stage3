# Stage 3 — Demonstration Video Guide (Lab Hardware)

This guide is a **step-by-step runbook** to record the required Stage 3 demo video in the laboratory.

Target duration: **4–7 minutes** (max 10).

Video title (required):

```
[Stage 3] Search Engine Project - <Group Name> (ULPGC)
```

Upload to YouTube as **Unlisted** (not Private), and paste the link in `README.md`.

---

## 0) What you must show (rubric checklist)

Your video must show, at minimum:

1) **Deployment** with Docker (or orchestration tool).
2) **Real-time ingestion + indexing + search** across multiple containers/nodes.
3) A **load test** demonstrating **horizontal scalability** as you launch additional instances.
4) A **simulated node failure** and **automatic recovery** (rerouting / resilience).
5) **Logs / monitoring** showing throughput, latency, and resource utilization.

This runbook is structured to hit those points in order.

---

## 1) Recording setup (recommended)

### Where to record
- Record on the **lab machines** using the official hardware setup.
- Ideally do the screen recording from the **master PC**.

### Tools
- Use **OBS Studio** (or equivalent) with:
  - Display capture (your screen)
  - Microphone (voice narration)

### Windows layout (keep it simple)
Have these visible or quickly accessible:
- Terminal window A: Docker deploy + monitoring
- Terminal window B: load test script
- Browser tab: ActiveMQ UI `http://<MASTER_IP>:8161`
- Optional browser tab: your Nginx entrypoint (search)

---

## 2) Topology for the demo (Master is also a worker)

You requested that the **master PC also runs crawler + indexer + search**, so it counts as a worker.

### Master PC runs:
- `activemq` (ports `61616`, `8161`)
- `nginx` (port `80`, optional but recommended)
- `crawler` (`8080`)
- `indexer` (`8081` + Hazelcast `5701`)
- `search` (`8082` + Hazelcast `5702`)

### Worker PCs run:
- `crawler`, `indexer`, `search`

This repo supports this using Docker Compose profiles.

### Important: how indexing is distributed

- When a crawler ingests a book, it publishes an ActiveMQ message with `sourceNodeIp=CURRENT_NODE_IP` of that crawler.
- Each indexer consumes messages using a JMS selector: it only consumes messages whose `sourceNodeIp` matches its own `CURRENT_NODE_IP`.

This means indexing is distributed **only if ingestion is distributed** (e.g., you send ingestion requests to multiple nodes).
If you ingest all books via the master crawler only, then **the master indexer will consume (almost) all indexing jobs**, even though replication stores copies on other nodes.

### Replication behavior (R=2)

Replication is best-effort and replicates each ingested book to **one other node**.
So when replication succeeds you get **2 total copies**:

- Copy 1: the node that ingested the book
- Copy 2: one other node

There is **no background re-replication/catch-up** for a node that was offline at ingestion time.
If you stop a node and later restart it, it does not automatically fetch old replicated books it missed.

---

## 3) One-time prep before going to the lab

### A) Build images once (optional)
If builds are slow in the lab, build beforehand:

```
docker compose build
```

### B) Confirm ports are allowed
- Master PC: `80/tcp`, `61616/tcp`, `8161/tcp`, plus worker ports below
- Worker PCs (including master-as-worker): `8080/tcp`, `8081/tcp`, `8082/tcp`, `5701/tcp`, `5702/tcp`

### C) Prepare a per-node `.env`
Each physical machine needs its own `.env` next to `docker-compose.yml`.

Minimal required:
- `MASTER_NODE_IP`: master machine IP
- `CURRENT_NODE_IP`: this machine’s IP
- `CLUSTER_NODES_LIST`: comma-separated list of **ALL worker IPs**, including the master (because master is also a worker)
- `HOST_DATA_VOLUME_PATH`: local path for datalake storage

Example (MASTER PC, also a worker):

```
MASTER_NODE_IP=192.168.1.10
CURRENT_NODE_IP=192.168.1.10
CLUSTER_NODES_LIST=192.168.1.10,192.168.1.11,192.168.1.12
HOST_DATA_VOLUME_PATH=/mnt/datalake

DATALAKE_REPLICATION_ENABLED=true
INGESTION_ASYNC_ENABLED=true
INGESTION_ASYNC_WORKERS=4
```

Example (WORKER PC):

```
MASTER_NODE_IP=192.168.1.10
CURRENT_NODE_IP=192.168.1.11
CLUSTER_NODES_LIST=192.168.1.10,192.168.1.11,192.168.1.12
HOST_DATA_VOLUME_PATH=/mnt/datalake

DATALAKE_REPLICATION_ENABLED=true
INGESTION_ASYNC_ENABLED=true
INGESTION_ASYNC_WORKERS=4

# Optional: indexer throughput tuning (recommended for demo load)
INDEXER_CONSUMERS=2
INDEXER_MAX_DELIVERIES=5
```

---

## 4) Video script (time-coded) — follow this exactly

### 0:00–0:30 — Intro + proof of lab environment
Show:
- Your master terminal with `hostname` and `ip a` (or equivalent)
- Briefly show you are on lab PCs

Say:
- “We’re running Stage 3 on the official lab hardware. This demo shows deployment, distributed ingestion/indexing/search, scalability under load, node failure and recovery, and monitoring.”

### 0:30–1:30 — Deployment with Docker (rubric #1)

On the master PC (Terminal A) from the repo folder:

1) Start **master services + worker services on master**:

```
docker compose --profile master --profile node up -d
```

2) Prove containers are running:

```
docker ps
```

3) Open ActiveMQ UI in the browser:

- `http://<MASTER_IP>:8161`

Narrate:
- “Master runs ActiveMQ and Nginx, and also runs crawler/indexer/search as a worker.”

On at least one worker PC (or SSH session), start node services:

```
docker compose --profile node up -d
```

### 1:30–2:40 — Real-time ingestion → indexing → search (rubric #2)

Pick the **master IP** as a worker node too (so you prove master participates). Use `<M>` for master IP.

1) Health checks (quick):

```
curl -s http://<M>:8080/health
curl -s http://<M>:8081/health
curl -s http://<M>:8082/health
```

2) Trigger ingestion (async):

```
curl -i -X POST "http://<M>:8080/ingest/1?async=true"
```

3) Poll job status until available:

```
watch -n 0.5 'curl -s http://<M>:8080/ingest/status/1'
```

4) Show indexing status changing:

```
watch -n 0.5 'curl -s http://<M>:8081/index/status'
```

5) Show search results:

```
curl -s "http://<M>:8082/search?q=hello&limit=5"
```

Narrate:
- “Ingestion downloads and stores into the datalake and publishes an ActiveMQ event. Indexers consume events and build the Hazelcast-backed inverted index. Search queries the distributed index.”

### 2:40–4:30 — Load test + horizontal scaling (rubric #3)

This repo includes a load generator:
- `scripts/lab_ingest_first_n.sh`

On the master PC, open monitoring first:

1) Resource usage:

```
docker stats
```

2) Indexing stats (at least two nodes):

```
watch -n 1 'echo MASTER; curl -s http://<M>:8081/index/status; echo; echo WORKER1; curl -s http://<W1>:8081/index/status'
```

Run load test against multiple nodes (include master as a worker):

```
scripts/lab_ingest_first_n.sh --nodes "<M> <W1>" --count 200 --concurrency 20 --async true
```

Now demonstrate horizontal scaling:

1) Start a new worker `<W2>` (on that machine):

```
docker compose --profile node up -d
```

2) Re-run load including the new node:

```
scripts/lab_ingest_first_n.sh --nodes "<M> <W1> <W2>" --count 200 --concurrency 30 --async true
```

Narrate:
- “When we add another instance, throughput increases: more ingestion capacity and more indexing happening in parallel across nodes.”

### 4:30–6:00 — Simulated node failure + recovery (rubric #4)

Pick one worker node `<W1>` and simulate failure.

Option A (simple, obvious): stop all services on `<W1>`:

On `<W1>`:

```
docker compose --profile node down
```

Now prove the system still works (request rerouting):
- Run search via Nginx on master:

```
curl -i "http://<MASTER_IP>/search?q=the&limit=5"
```

- Also show a direct search against another surviving node (master itself is fine):

```
curl -s "http://<M>:8082/search?q=the&limit=5"
```

Bring the failed node back:

On `<W1>`:

```
docker compose --profile node up -d
```

Narrate:
- “When a node fails, Nginx continues serving requests by routing to remaining nodes. When the node comes back, it rejoins the cluster.”

### 6:00–7:00 — Monitoring proof (rubric #5)

Show at least two of these, quickly:

- ActiveMQ UI queue depth moving
- `docker stats` showing CPU/memory/network
- Quick latency sample:

```
time curl -s "http://<MASTER_IP>/search?q=the&limit=10" >/dev/null
```

End with:
- “Deployment, distributed operations, scaling, failure and recovery, and monitoring have been demonstrated.”

---

## 5) After the recording

1) Upload to YouTube as **Unlisted**.
2) Ensure the title matches the required format exactly.
3) Add the link to `README.md` (top section recommended):

Example snippet:

```md
## Demo Video

Unlisted YouTube: https://youtu.be/<your-id>
```

---

## 6) Common demo pitfalls (avoid these)

- **Not including master in `CLUSTER_NODES_LIST`** while master runs as a worker.
- **Starting only `--profile master`** on master. For master-as-worker you must start both:
  - `docker compose --profile master --profile node up -d`
- **Not showing “real-time”**: always use `watch`/`docker stats`/ActiveMQ UI so changes are visible.
- **Failure demo doesn’t prove recovery**: after stopping a node, show that queries still succeed (preferably via Nginx).

### Troubleshooting: JVM fails to start with JAVA_TOOL_OPTIONS

If you ever see logs like:

```
Picked up JAVA_TOOL_OPTIONS: ...
Unrecognized option: --add-modules
Error: Could not create the Java Virtual Machine.
```

It means the Java runtime used in that container/host is rejecting module flags.
For the lab demo, keep the compose setup **without** those flags (the project already suppresses Hazelcast noise via logging config).

