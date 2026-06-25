# Stage 3 — Cluster Deployment Guide

Step-by-step instructions for running the distributed search engine on **multiple physical lab PCs** without Docker Swarm or Kubernetes. Covers everything from cloning to benchmarking.

---

## Architecture Quick Reference

```
┌──────────────────────────────────────────────────────────────┐
│  MASTER NODE  (one machine — usually PC1)                    │
│  ActiveMQ :61616/:8161     Nginx :80 (least_conn LB)         │
└──────────────────────────────────────────────────────────────┘
              ↑ JMS            ↓ proxy /search → :8082
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│   PC1 / node  │  │   PC2 / node  │  │   PC3 / node  │
│  crawler :8080│  │  crawler :8080│  │  crawler :8080│
│  indexer :8081│  │  indexer :8081│  │  indexer :8081│
│  HZ-idx  :5701│  │  HZ-idx  :5701│  │  HZ-idx  :5701│
│  search  :8082│  │  search  :8082│  │  search  :8082│
│  HZ-srch :5702│  │  HZ-srch :5702│  │  HZ-srch :5702│
└───────────────┘  └───────────────┘  └───────────────┘
        └──────── Hazelcast TCP-IP cluster ─────────┘
```

PC1 typically runs both the master profile (ActiveMQ + Nginx) and the node profile (all three services). Every other PC runs only the node profile.

---

## Required Open Ports

Every PC must be able to reach every other PC on these ports.

| Port  | Service                 | Traffic direction       |
|-------|-------------------------|-------------------------|
| 80    | Nginx load balancer     | Clients → master        |
| 8080  | Crawler / datalake API  | All nodes ↔ all nodes   |
| 8081  | Indexer (internal)      | Optional external access|
| 8082  | Search service          | Nginx → all nodes       |
| 5701  | Hazelcast (indexer)     | All nodes ↔ all nodes   |
| 5702  | Hazelcast (search)      | All nodes ↔ all nodes   |
| 61616 | ActiveMQ broker         | All nodes → master      |
| 8161  | ActiveMQ web console    | Browser → master        |

---

## Step 0 — Prerequisites (do this once per PC)

Install Docker Engine (v24+) and Docker Compose v2:

```bash
# Verify
docker --version
docker compose version
```

Install Git:

```bash
git --version
```

---

## Step 1 — Clone the Repository on Every PC

Run this on **each physical PC** that will participate in the cluster:

```bash
git clone https://github.com/bigdatanombredefault/stage3.git
cd stage3
```

All PCs must have the repository at the same relative path (or adapt the commands below accordingly).

---

## Step 2 — Update the Shared IPs (group leader only, once)

One person (the group leader) updates `cluster-shared.env` with the real lab IPs and pushes to GitHub. **Every other PC then does a `git pull`** to receive the updated IPs.

### A) Find each PC's LAN IP

On each PC:

```bash
ip addr show 
```

Write them down. Example for this guide:
- **PC1** (master + node): `10.26.14.202`
- **PC2** (node): `10.26.14.201`
- **PC3** (node): `10.26.14.200`

### B) Edit `cluster-shared.env`

Open `cluster-shared.env` and fill in the real IPs:

```bash
# cluster-shared.env (example for 3 nodes)
MASTER_NODE_IP=10.26.14.200
CLUSTER_NODES_LIST=10.26.14.200,10.26.14.201,10.26.14.202

PC1_IP=10.26.14.200
PC2_IP=10.26.14.201
PC3_IP=10.26.14.202
PC4_IP=10.26.14.200   # duplicate for 3-node cluster
PC5_IP=10.26.14.201
PC6_IP=10.26.14.202
```

### C) Commit and push

```bash
git add cluster-shared.env
git commit -m "chore: update lab IPs for session"
git push
```

### D) Every other PC pulls the update

On **PC2, PC3, ... PCN**:

```bash
cd stage3
git pull
```

---

## Step 3 — Create the Per-Machine `.env` File

Each PC needs its own `.env` file. It combines the shared IPs from `cluster-shared.env` with one machine-specific value: `CURRENT_NODE_IP`.

Run this one-liner on each PC (replace `<THIS_PC_IP>` with the IP you found in Step 2A):

```bash
cp cluster-shared.env .env
echo "CURRENT_NODE_IP=10.26.14.200" >> .env   # change this IP per machine!
```

Or copy from the template and fill in all values manually:

```bash
cp .env.example .env
# Edit with nano/vim: set CURRENT_NODE_IP to this machine's LAN IP
```

### Resulting `.env` for PC1 (master + node)

```bash
MASTER_NODE_IP=10.26.14.200
CLUSTER_NODES_LIST=10.26.14.200,10.26.14.201,10.26.14.202
PC1_IP=10.26.14.200
PC2_IP=10.26.14.201
PC3_IP=10.26.14.202
PC4_IP=10.26.14.200
PC5_IP=10.26.14.201
PC6_IP=10.26.14.202

CURRENT_NODE_IP=10.26.14.200   # ← THIS LINE IS DIFFERENT ON EVERY PC
```

### Resulting `.env` for PC2 (node only)

Same as above, but the last line differs:

```bash
CURRENT_NODE_IP=10.26.14.201   # ← PC2's own IP
```

> **Important:** `CURRENT_NODE_IP` is what Hazelcast advertises to other cluster members. If it is `localhost` or blank, other nodes cannot reach this machine and the cluster will not form.

---

## Step 4 — Build the Docker Images (every PC)

Each machine must build its own images:

```bash
cd stage3
docker compose build
```

This compiles the Java services and packages them into containers. It only needs to run once, or again if you change the source code.

---

## Step 5 — Start the Cluster

### PC1 — master + node (starts first)

```bash
docker compose --profile master --profile node up -d
```

This starts: `activemq`, `nginx`, `crawler`, `indexer`, `search`.

Verify the master is up:

```bash
docker compose ps
curl http://localhost/
# Expected: "Stage 3 is running. Use /search?q=<term>..."

# ActiveMQ web console:
# http://10.26.14.200:8161  (admin / admin)
```

### PC2, PC3, ... PCN — node only

```bash
docker compose --profile node up -d
```

This starts: `crawler`, `indexer`, `search` on that machine.

---

## Step 6 — Verify Hazelcast Cluster Formation

After all nodes are up (wait ~30 seconds for discovery):

```bash
# Check that all indexer members found each other (expect size:N):
docker compose logs indexer 2>&1 | grep -E "Members \{size"

# Expected output for 3 nodes:
# Members {size:3, ver:3} [
#     Member [10.26.14.200]:5701 - ...
#     Member [10.26.14.201]:5701 - ...
#     Member [10.26.14.202]:5701 - ...
# ]
```

If you see `size:1` on every node, the cluster has not formed. See Troubleshooting below.

---

## Step 7 — Ingest Books

Distribute ingestion requests across all nodes using the provided script:

```bash
# From any machine that can reach all workers:
bash scripts/lab_ingest_first_n.sh \
  --nodes "10.26.14.200 10.26.14.201 10.26.14.202" \
  --count 200 \
  --concurrency 30 \
  --async true
```

The script sends `POST /ingest/{bookId}?async=true` in round-robin across nodes. Each node downloads its own subset of books, replicates each one to a peer (R=2), then publishes a JMS event for the indexer.

Monitor ingestion progress:

```bash
watch -n 2 'curl -s http://10.26.14.200:8080/ingest/list | wc -c'
watch -n 2 'curl -s http://10.26.14.200:8081/index/status'
```

---

## Step 8 — Test Search

```bash
# Search through Nginx (load balancer, shows which node responded):
curl -i "http://10.26.14.200/search?q=whale&limit=5"
# Look for: X-Upstream-Addr header — shows which PC served the request

# Search directly on a node:
curl "http://10.26.14.201:8082/search?q=whale&limit=5"

# Index stats (global view across all Hazelcast nodes):
curl "http://10.26.14.200:8082/stats"

# List indexed books:
curl "http://10.26.14.200:8082/books?limit=10"
```

---

## Updating IPs Mid-Session

If the lab assigns different IPs in a new session:

```bash
# Group leader only:
nano cluster-shared.env    # update IPs
git add cluster-shared.env
git commit -m "chore: update lab IPs"
git push

# Every PC:
git pull
cp cluster-shared.env .env
echo "CURRENT_NODE_IP=<THIS_PC_IP>" >> .env

# Restart services to pick up new config:
docker compose --profile node down
docker compose --profile node up -d
# (master PC: use --profile master --profile node)
```

---

## Running the Benchmarks

The benchmark suite runs four test phases matching the §4.4 requirements from the project guide.

```bash
# Full benchmark suite — writes results to benchmarks/results/
bash scripts/benchmark.sh \
  --master 10.26.14.200 \
  --workers "10.26.14.200 10.26.14.201 10.26.14.202"
```

### What the benchmark measures

| Phase | What it does | Metric |
|-------|-------------|--------|
| Baseline | Ingest + search with 1 node | Ingestion rate, query latency |
| Scaling | Repeat with 3 nodes | Throughput increase |
| Load | `wrk` concurrent search queries | avg/p95/max latency |
| Failure | Stop one node mid-query | Recovery time, request errors |

Results are written to `benchmarks/results/`. See `benchmarks/README.md` for the expected format.

---

