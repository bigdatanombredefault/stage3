# Benchmarks

This directory stores performance results from the Stage 3 benchmark suite.

## Running the benchmarks

```bash
bash scripts/benchmark.sh \
  --master 10.26.14.200 \
  --workers "10.26.14.200 10.26.14.201 10.26.14.202"
```

Each run creates a timestamped folder under `results/`:

```
results/
└── 20260430_143500/
    ├── summary.txt          # Key metrics from all phases
    ├── phase1_baseline.txt  # Ingestion log (1 node)
    ├── phase2_scaling.txt   # Ingestion log (N nodes)
    ├── phase3_load.txt      # wrk output (query latency)
    ├── phase3_docker_stats.txt  # CPU/memory snapshot
    └── phase4_failure.txt   # Failure + recovery probe log
```

## Metrics to collect (§4.3)

| Metric | Source | Where to find it |
|--------|--------|-----------------|
| Ingestion rate (docs/s) | `benchmark.sh` | `summary.txt` |
| Indexing throughput | `GET /index/status` | Manual observation |
| Query latency avg/p95/max | `wrk` | `phase3_load.txt` |
| CPU / memory per node | `docker stats` | `phase3_docker_stats.txt` |
| Recovery time | `benchmark.sh` | `summary.txt` Phase 4 |

## Expected result format (Table 1 from project guide)

Fill this in after running benchmarks on actual hardware:

| # Nodes | Ingestion Rate (docs/s) | Query Latency (ms) | CPU (%) | Recovery (s) |
|---------|-------------------------|--------------------|---------|--------------|
| 1       |                         |                    |         | N/A          |
| 3       |                         |                    |         |              |
| 6 (*)   |                         |                    |         |              |

(*) If lab only has 3 PCs, report 1-node vs 3-node comparison.

## Notes on Gutenberg dataset

Project Gutenberg IDs are sparse — some IDs return 404 or are unavailable in the requested format. A ~30% failure rate on ingest is normal. What matters is the throughput of **successful** downloads.

The `lab_ingest_first_n.sh` script logs each request with its HTTP status so you can count successes vs failures from the log.
