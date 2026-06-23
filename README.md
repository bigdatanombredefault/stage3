# Stage 3 — Distributed Search Engine Cluster

**Big Data · Grado en Ciencia e Ingeniería de Datos · ULPGC**

A fault-tolerant, horizontally scalable search engine that distributes ingestion, indexing, and querying across multiple physical nodes. Built on Apache ActiveMQ, Hazelcast, and Nginx as the load balancer.

---

## Demo Video

> **YouTube (unlisted):** _[ADD LINK HERE BEFORE SUBMISSION]_
>
> Title must be: `[Stage 3] Search Engine Project - <Group Name> (ULPGC)`
>
> See [`docs/video_guide.md`](docs/video_guide.md) for the full recording runbook.

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│  MASTER NODE                                     │
│  ActiveMQ :61616   Nginx :80 (least_conn LB)     │
└──────────────────────────────────────────────────┘
         ↑ JMS messages          ↓ HTTP search
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  PC1 (node) │  │  PC2 (node) │  │  PC3 (node) │
│  crawler    │  │  crawler    │  │  crawler    │
│  :8080      │  │  :8080      │  │  :8080      │
│  indexer    │  │  indexer    │  │  indexer    │
│  :8081/:5701│  │  :8081/:5701│  │  :8081/:5701│
│  search     │  │  search     │  │  search     │
│  :8082/:5702│  │  :8082/:5702│  │  :8082/:5702│
└─────────────┘  └─────────────┘  └─────────────┘
        └──── Hazelcast cluster (TCP-IP) ────┘
```

**Multi-service node topology:** each physical PC runs crawler + indexer + search together. This maximises data locality (the indexer only processes books its own crawler downloaded) and reduces inter-node latency.

### How data flows

1. **Crawling** — `POST /ingest/{id}` downloads a Project Gutenberg book, stores it in the local datalake partition, and replicates it to one peer node (R=2). The crawler then publishes a JMS message stamped with `sourceNodeIp`.
2. **Indexing** — each indexer subscribes with a JMS selector (`sourceNodeIp = 'CURRENT_NODE_IP'`) so it only processes books it already has locally. It updates the distributed Hazelcast `MultiMap` (inverted index) with `FencedLock` for concurrent safety.
3. **Searching** — queries arrive through Nginx, are routed via `least_conn` to any search node, which scans the entire distributed Hazelcast index and returns ranked results.

---

## Components

| Component | Technology | Ports |
|-----------|-----------|-------|
| Crawler / Datalake | Spring Boot | `:8080` |
| Indexer | Spring Boot + Hazelcast | `:8081`, `:5701` |
| Search | Spring Boot + Hazelcast | `:8082`, `:5702` |
| Message broker | Apache ActiveMQ | `:61616`, `:8161` |
| Load balancer | Nginx | `:80` |

---

## Quick Start (single machine)

```bash
git clone <repo-url>
cd stage3

# Copy and edit the environment file
cp .env.example .env
# Set CURRENT_NODE_IP, MASTER_NODE_IP, CLUSTER_NODES_LIST to your LAN IP
# Set PC1_IP=PC2_IP=PC3_IP=PC4_IP=PC5_IP=PC6_IP to your LAN IP

# Build all service images
docker compose build

# Start everything on one machine
docker compose --profile master --profile node up -d

# Verify
curl http://localhost/
curl http://localhost:8080/health
curl http://localhost:8081/health
curl http://localhost:8082/health
```

---

## Cluster Deployment (multiple physical lab PCs)

See [`CLUSTER_DEPLOYMENT.md`](CLUSTER_DEPLOYMENT.md) for the full step-by-step guide, including:
- How to find each PC's LAN IP
- The git-based shared IP workflow (push once, pull on every PC)
- Starting master and worker nodes
- Verifying Hazelcast cluster formation
- Troubleshooting firewall and connectivity issues

---

## API Reference

### Crawler — `:8080`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Service health |
| POST | `/ingest/{id}` | Ingest a book (async by default → 202) |
| POST | `/ingest/{id}?async=false` | Synchronous ingest → 201 |
| GET | `/ingest/status/{id}` | Poll async job status |
| GET | `/ingest/list` | List book IDs on this node |
| POST | `/api/datalake/store` | Replication receiver (internal) |

### Indexer — `:8081`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Service health |
| GET | `/index/status` | Indexed count, unique words |
| POST | `/index/update/{id}` | Manually index one book |
| POST | `/index/rebuild` | Rebuild full index from local datalake |

### Search — `:8082`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Service health |
| GET | `/stats` | Index stats (total books, unique terms) |
| GET | `/books?limit=N` | List indexed books |
| GET | `/search?q=term&limit=N` | Search (supports `author`, `language`, `year` filters) |

---

## Benchmarking

### Reproduce the benchmarks

```bash
# Prerequisites: wrk installed, cluster running, at least 100 books indexed
#   brew install wrk / apt install wrk

# Run the full benchmark suite (baseline → scaling → load → failure)
bash scripts/benchmark.sh --master 192.168.1.10 --workers "192.168.1.10 192.168.1.11 192.168.1.12"

# Results are written to benchmarks/results/
```

See [`docs/guide.md`](docs/guide.md) for detailed benchmark procedures and interpretation guidance.

### Metrics collected

| Metric | Tool |
|--------|------|
| Ingestion rate (docs/s) | `lab_ingest_first_n.sh` + timing |
| Query latency (avg, p95, max) | `wrk` |
| CPU / memory per node | `docker stats` |
| Recovery time after node failure | Manual timing |

Results tables and charts belong in `benchmarks/results/`. See [`benchmarks/README.md`](benchmarks/README.md).

---

## Repository Structure

```
stage3/
├── ingestion-service/       # Crawler + datalake + replication
├── indexing-service/        # Hazelcast member + JMS indexer
├── search-service/          # Hazelcast member + REST search
├── docker-compose.yml       # All services (profiles: master / node)
├── nginx.conf.template      # envsubst template for Nginx upstream
├── cluster-shared.env       # Shared IPs — commit once, git pull on each PC
├── .env.example             # Template for per-machine .env
├── CLUSTER_DEPLOYMENT.md    # Full lab deployment guide
├── scripts/
│   ├── lab_ingest_first_n.sh  # Distributed ingestion load generator
│   └── benchmark.sh           # Full benchmark suite
├── benchmarks/
│   ├── README.md              # What to record and how to structure results
│   └── results/               # CSV / log files from actual runs (gitignored by default)
└── docs/
    ├── guide.md               # Operational guide (detailed)
    └── video_guide.md         # Demo video recording runbook
```

---

## Design Decisions

**Why multi-service nodes instead of single-service nodes?**
Data locality: each indexer processes only books stored on the same physical disk, eliminating remote datalake reads during indexing. The trade-off is coupled service lifecycles per node, which is acceptable in a controlled lab environment.

**Why embedded Hazelcast members instead of standalone containers?**
Each service IS a Hazelcast member — it stores real data partitions. This reduces network hops between the indexer writing to the index and the data store holding it. The alternative (client/server mode) would be cleaner but adds a layer of containers per node, which complicates the lab setup.

**Why TCP-IP discovery instead of multicast?**
Lab networks typically block multicast. TCP-IP with an explicit `CLUSTER_NODES_LIST` is more reliable and easier to reason about. `CURRENT_NODE_IP` must be the machine's LAN IP — never `localhost` — so Hazelcast advertises a reachable address to peers.

**Why hardcode `hazelcast.cluster.name`?**
Making the cluster name configurable risks split-brain if different PCs accidentally use different values. Hardcoding `search-cluster` is simpler and eliminates that failure mode.

---

## Fault Tolerance

- **Datalake:** replication factor R=2 — each book is stored on 2 nodes. If one node fails, the book is still accessible for reindexing.
- **Hazelcast index:** `backupCount=2`, `asyncBackupCount=1` — the inverted index survives up to 2 simultaneous node failures without data loss.
- **ActiveMQ:** at-least-once delivery with configurable redelivery (`INDEXER_MAX_DELIVERIES`). Idempotent indexing prevents duplicate entries.
- **Nginx:** `max_fails=3 fail_timeout=15s` — a failed search node is automatically removed from the pool; requests reroute to healthy nodes instantly.
- **CP Subsystem FencedLock:** used for concurrent inverted index writes when ≥3 Hazelcast members are present. Falls back to a local `ReentrantLock` for smaller clusters.

---

## Versions used

| Software | Version |
|----------|---------|
| Java | 17 |
| Spring Boot | 3.x |
| Hazelcast | 5.x |
| ActiveMQ | 5.x (`rmohr/activemq:latest`) |
| Nginx | latest |
| Docker Compose | v2 |
