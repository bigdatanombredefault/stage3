# Running Stage 3 in a Physical Lab Cluster

This stage runs a search engine as 3 Java services:

- **ingestion-service** (crawler + datalake + replication) on `:8080`
- **indexing-service** (Hazelcast member + JMS consumer) on `:8081` + Hazelcast `:5701`
- **search-service** (REST API + Hazelelcast member) on `:8082` + Hazelcast `:5702`

It is designed for **physical nodes with static IPs** using **Docker Compose profiles** (no Swarm, no overlay DNS).

## Recommended topology

This is the simplest topology for the lab and avoids "book not found" failures:

- **Master PC (1 node)**
  - `activemq` (ports `61616`, `8161`)
  - `nginx` load balancer (port `80`, optional)

- **Worker PCs (N nodes)**
  - run **all three services** on each worker:
    - `crawler` (`8080`)
    - `indexer` (`8081` + `5701`)
    - `search` (`8082`)

Notes:

- The indexer runs a Hazelcast **member** on `5701`.
- The search service runs as a Hazelcast **member** too, but on a different port (`5702`) so it can coexist on the same host.

## Ports to allow

- Master PC: `80/tcp`, `61616/tcp`, `8161/tcp`
- Worker PCs: `8080/tcp`, `8081/tcp`, `8082/tcp`, `5701/tcp`, `5702/tcp`

## Per-node configuration (.env)

Each physical PC needs its own `.env` file next to `docker-compose.yml`.

### Required variables (workers)

- `MASTER_NODE_IP`: IP of the master PC (ActiveMQ + Nginx)
- `CURRENT_NODE_IP`: IP of *this* worker PC (must be correct)
- `CLUSTER_NODES_LIST`: comma-separated list of worker node IPs.
  - You can also use explicit `IP:PORT` entries, but it is not required.
  - Each IP is expanded automatically to the known Hazelcast member ports (defaults: `5701` and `5702`).
  - Optional override: set `hazelcast.member.ports=5701,5702` (or your custom ports).
- `HOST_DATA_VOLUME_PATH`: path on this PC for datalake storage

### Optional variables

- `DATALAKE_REPLICATION_ENABLED=true|false` (default: `true`)
- `INGESTION_ASYNC_ENABLED=true|false` (default in compose: `true`)
- `INGESTION_ASYNC_WORKERS=4` (tune for load)

### Example worker `.env` (node profile)

```
MASTER_NODE_IP=192.168.1.10
CURRENT_NODE_IP=192.168.1.12

# all worker nodes (each runs crawler+indexer+search)
CLUSTER_NODES_LIST=192.168.1.11,192.168.1.12,192.168.1.13,192.168.1.14

HOST_DATA_VOLUME_PATH=/mnt/datalake
DATALAKE_REPLICATION_ENABLED=true

INGESTION_ASYNC_ENABLED=true
INGESTION_ASYNC_WORKERS=4
```

## How to run

### 1) Base test (1 PC only: master + node)

On a single PC, you will only have **2 Hazelcast members** (`indexer` + `search`).
Hazelcast CP requires **3+ members** to form a CP group, so the indexer will temporarily use **local JVM locks** while the cluster has < 3 members.

Start everything on the same PC:

```
docker compose --profile master --profile node up -d
```

### 2) Lab configuration (3 / 6 / 9 PCs)

For real lab scaling tests, keep **1 PC as master+node**, and all other PCs as **node-only**.

On the master PC:

```
docker compose --profile master --profile node up -d
```

On every other PC:

```
docker compose --profile node up -d
```

In these configurations you’ll have at least 3 Hazelcast members very quickly (each PC contributes 2 members: indexer + search).

ActiveMQ web UI:

- `http://<MASTER_NODE_IP>:8161`

### Notes for all configurations

- Ensure `CURRENT_NODE_IP` is set to the **physical machine IP** (not `localhost`) when you’re on the lab network.
- Ensure `CLUSTER_NODES_LIST` contains **all worker IPs**, including the master (because master is also a worker).

## Nginx load balancer (optional)

If you want a single stable entrypoint for search:

1) Set `PC1_IP`–`PC6_IP` in `.env` on the master to the real worker IPs running search on `:8082`.
   For a 3-node cluster repeat IPs: `PC4_IP=PC1_IP`, `PC5_IP=PC2_IP`, `PC6_IP=PC3_IP`.
2) Restart the nginx container so the entrypoint re-runs `envsubst` on `nginx.conf.template`:

```
docker compose restart nginx
```

> **Note:** `nginx -s reload` does NOT re-run `envsubst`. You must restart the container for IP changes to take effect.

Load-balanced entrypoint:

- `http://<MASTER_NODE_IP>/search?q=<term>`

## HTTP endpoints (reference)

### ingestion-service (crawler) — `:8080`

- `GET /health`
- `POST /ingest/{book_id}` (async by default in compose; returns `202`)
- `POST /ingest/{book_id}?async=true` (force async)
- `POST /ingest/{book_id}?async=false` (force sync; returns `201`)
- `GET /ingest/status/{book_id}` (poll async job)
- `GET /ingest/list` (downloaded book ids on this node)
- `POST /api/datalake/store` (replication receiver)

### indexing-service (indexer) — `:8081`

- `GET /health`
- `GET /index/status`
- `POST /index/update/{book_id}` (manual index for a book id)
- `POST /index/rebuild` (force rebuild from local datalake)
- `GET /stats` (alias for stats; kept for compatibility)

### search-service — `:8082`

- `GET /health`
- `GET /stats`
- `GET /books?limit=N`
- `GET /search?q=term&limit=N&author=...&language=...&year=...`

## End-to-end test (ingest → index → search)

Use a worker IP (replace `<W1>` with a real worker IP).

### 1) Health checks

```
curl -s http://<W1>:8080/health
curl -s http://<W1>:8081/health
curl -s http://<W1>:8082/health
```

### 2) Trigger ingestion (async)

```
curl -s -X POST "http://<W1>:8080/ingest/1?async=true"
```

Expected: HTTP `202` and a JSON body with a `status` like `queued`/`downloading`.

### 3) Poll until the file exists locally

```
curl -s http://<W1>:8080/ingest/status/1
```

Wait until `status=available`. If it becomes `failed`, check crawler logs.

Optional: verify the downloaded list on that node:

```
curl -s http://<W1>:8080/ingest/list
```

### 4) Verify indexing happened on the same node

Because of “index where ingested”, the indexer on the same worker should consume the message.

Check stats:

```
curl -s http://<W1>:8081/index/status
```

Expected: `books_indexed` increases and `unique_words` becomes > 0.

If you want to force it manually:

```
curl -s -X POST http://<W1>:8081/index/update/1
```

### 5) Verify search sees indexed data

First check global index stats from the search service:

```
curl -s http://<W1>:8082/stats
```

Then browse a few books:

```
curl -s "http://<W1>:8082/books?limit=5"
```

Finally try a search query:

```
curl -s "http://<W1>:8082/search?q=the&limit=10"
```

If Nginx is configured:
```
curl -i "http://<MASTER_NODE_IP>/search?q=the&limit=5" | grep -i X-Upstream-Addr
```

## What to check when something is wrong

### Ingestion stuck in `queued` / `downloading`

- Check crawler container logs: `docker logs <crawler-container-name>`
- Verify the node can reach the internet (book download).
- If replication is enabled, verify workers can reach each other on `8080/tcp`.

### Indexing does not happen

- Confirm `BROKER_URL` points to the master (it should, via `MASTER_NODE_IP`).
- Confirm `CURRENT_NODE_IP` is correct.
  - If it is wrong, the indexer’s JMS selector won’t match `sourceNodeIp`, and it will never consume messages.
- Check indexer logs for ActiveMQ connection and message consumption.

### Book exists on one node only (no replication)

- With replication R=2 you expect each ingested book to appear on the source node and on exactly 1 peer node.
- If a book stays only on the source node:
  - Ensure `DATALAKE_REPLICATION_ENABLED=true`.
  - Ensure `CLUSTER_NODES_LIST` on that node includes the other worker IPs (and not just itself).
  - Ensure worker-to-worker `8080/tcp` connectivity (firewall).

### Search returns empty results

- Confirm Hazelcast member ports (`5701/tcp`) are reachable between workers.
- Confirm search is configured with `CLUSTER_NODES_LIST` containing worker IPs (Hazelcast members).

## Disaster recovery

If Hazelcast state is lost (restart), indexers can rebuild from local datalake:

```
curl -s -X POST http://<W1>:8081/index/rebuild
```

Reminder: rebuild is limited to what exists on that node’s disk; replication (R=2) improves durability.