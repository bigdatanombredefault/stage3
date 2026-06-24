"""
Generate all benchmark figures for Stage 3 benchmark_report.tex
Output: PDF files suitable for Overleaf (\includegraphics)
"""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np
from pathlib import Path

OUT = Path(__file__).parent
OUT.mkdir(parents=True, exist_ok=True)

# ── Consistent style ──────────────────────────────────────────────────────────
plt.rcParams.update({
    "font.family": "serif",
    "font.size": 10,
    "axes.titlesize": 11,
    "axes.labelsize": 10,
    "xtick.labelsize": 9,
    "ytick.labelsize": 9,
    "legend.fontsize": 9,
    "figure.dpi": 150,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.grid": True,
    "axes.grid.axis": "y",
    "grid.alpha": 0.35,
    "grid.linestyle": "--",
})

BLUE   = "#2166ac"
ORANGE = "#d6604d"
GREEN  = "#4dac26"
GRAY   = "#888888"

CONFIGS = ["A (1 node)", "B (3 nodes)", "C (6 nodes)"]
SHORT   = ["Config A\n(1 node)", "Config B\n(3 nodes)", "Config C\n(6 nodes)"]

# ══════════════════════════════════════════════════════════════════════════════
# Figure 1 — Ingestion throughput (Phase 2, parallel)
# ══════════════════════════════════════════════════════════════════════════════
fig, ax = plt.subplots(figsize=(5.5, 3.5))

rates   = [0.18, 0.14, 0.41]
colors  = [BLUE, ORANGE, GREEN]
x       = np.arange(3)

bars = ax.bar(x, rates, color=colors, width=0.5, edgecolor="white", linewidth=0.8,
              zorder=3)
for bar, val in zip(bars, rates):
    ax.text(bar.get_x() + bar.get_width() / 2, val + 0.008,
            f"{val:.2f}", ha="center", va="bottom", fontsize=9, fontweight="bold")

ax.set_xticks(x)
ax.set_xticklabels(SHORT)
ax.set_ylabel("Ingestion rate (docs / s)")
ax.set_ylim(0, 0.52)
ax.set_title("Phase 2 — Parallel ingestion throughput by cluster size")
ax.tick_params(axis="x", length=0)

fig.tight_layout()
fig.savefig(OUT / "fig_ingestion_rate.pdf", bbox_inches="tight")
fig.savefig(OUT / "fig_ingestion_rate.png", bbox_inches="tight")
plt.close()
print("fig_ingestion_rate done")

# ══════════════════════════════════════════════════════════════════════════════
# Figure 2 — Latency percentiles grouped bar chart (Avg, p95, p99)
# ══════════════════════════════════════════════════════════════════════════════
latency = {
    "Avg":  [68.68, 48.25, 36.08],
    "p95":  [105.89, 76.62, 57.46],
    "p99":  [153.80, 105.24, 79.01],
}
metric_colors = [BLUE, ORANGE, GREEN]
n_groups  = 3
n_metrics = 3
width     = 0.23
x         = np.arange(n_groups)

fig, ax = plt.subplots(figsize=(6.5, 3.8))

for i, (label, vals) in enumerate(latency.items()):
    offset = (i - 1) * width
    bars = ax.bar(x + offset, vals, width, label=label,
                  color=metric_colors[i], edgecolor="white", linewidth=0.6,
                  zorder=3)
    for bar, val in zip(bars, vals):
        ax.text(bar.get_x() + bar.get_width() / 2, val + 1.5,
                f"{val:.0f}", ha="center", va="bottom", fontsize=7.5)

ax.set_xticks(x)
ax.set_xticklabels(SHORT)
ax.set_ylabel("Latency (ms)")
ax.set_ylim(0, 185)
ax.set_title("Phase 3 — Query latency percentiles by cluster size\n(50 concurrent, 30 s)")
ax.legend(framealpha=0.85)
ax.tick_params(axis="x", length=0)

fig.tight_layout()
fig.savefig(OUT / "fig_latency_bars.pdf", bbox_inches="tight")
fig.savefig(OUT / "fig_latency_bars.png", bbox_inches="tight")
plt.close()
print("fig_latency_bars done")

# ══════════════════════════════════════════════════════════════════════════════
# Figure 3 — Latency percentile profiles (line chart)
# ══════════════════════════════════════════════════════════════════════════════
pcts = ["p50", "p75", "p90", "p95", "p99"]
profiles = {
    "Config A (1 node)":  [51.61,  65.73,  85.64, 105.89, 153.80],
    "Config B (3 nodes)": [39.68,  50.69,  64.81,  76.62, 105.24],
    "Config C (6 nodes)": [34.44,  41.51,  50.27,  57.46,  79.01],
}
line_styles = ["-o", "-s", "-^"]
line_colors = [BLUE, ORANGE, GREEN]

fig, ax = plt.subplots(figsize=(6.5, 3.8))

for (label, vals), ls, col in zip(profiles.items(), line_styles, line_colors):
    ax.plot(pcts, vals, ls, label=label, color=col,
            linewidth=1.8, markersize=6, markeredgecolor="white",
            markeredgewidth=0.8, zorder=3)
    for px, val in zip(pcts, vals):
        ax.annotate(f"{val:.0f}", (px, val),
                    textcoords="offset points", xytext=(0, 7),
                    ha="center", fontsize=7.5, color=col)

ax.set_xlabel("Percentile")
ax.set_ylabel("Latency (ms)")
ax.set_title("Phase 3 — Latency percentile profiles across all configurations")
ax.legend(framealpha=0.85)
ax.set_ylim(0, 190)

fig.tight_layout()
fig.savefig(OUT / "fig_latency_profile.pdf", bbox_inches="tight")
fig.savefig(OUT / "fig_latency_profile.png", bbox_inches="tight")
plt.close()
print("fig_latency_profile done")

# ══════════════════════════════════════════════════════════════════════════════
# Figure 4 — Request throughput with linear scaling reference
# ══════════════════════════════════════════════════════════════════════════════
nodes     = [1, 3, 6]
throughput = [646.7, 919.3, 1237.8]
linear_ref = [646.7, 646.7 * 3, 646.7 * 6]   # perfect linear scaling

fig, ax = plt.subplots(figsize=(5.5, 3.8))

x = np.arange(3)
bars = ax.bar(x, throughput, color=[BLUE, ORANGE, GREEN], width=0.5,
              edgecolor="white", linewidth=0.8, zorder=3, label="Measured")

for bar, val in zip(bars, throughput):
    ax.text(bar.get_x() + bar.get_width() / 2, val + 18,
            f"{val:,.0f}", ha="center", va="bottom", fontsize=9, fontweight="bold")

ax.plot(x, linear_ref, "D--", color=GRAY, linewidth=1.5, markersize=7,
        markeredgecolor="white", markeredgewidth=0.8,
        label="Linear scaling (ideal)", zorder=4)

ax.set_xticks(x)
ax.set_xticklabels(SHORT)
ax.set_ylabel("Requests / second")
ax.set_ylim(0, 4500)
ax.set_title("Phase 3 — Query throughput by cluster size\n(50 concurrent, 30 s)")
ax.legend(framealpha=0.85)
ax.tick_params(axis="x", length=0)

# Annotate efficiency
ax.annotate("32 % scaling\nefficiency at 6×",
            xy=(2, 1237.8), xytext=(1.45, 2200),
            arrowprops=dict(arrowstyle="->", color="black", lw=1.2),
            fontsize=8.5, ha="center")

fig.tight_layout()
fig.savefig(OUT / "fig_throughput.pdf", bbox_inches="tight")
fig.savefig(OUT / "fig_throughput.png", bbox_inches="tight")
plt.close()
print("fig_throughput done")

# ══════════════════════════════════════════════════════════════════════════════
# Figure 5 — CPU utilisation per node (all configs, side by side)
# ══════════════════════════════════════════════════════════════════════════════
# Data: (node_label, indexer_cpu, search_cpu, estimated)
cpu_data = {
    "A": [
        ("PC-1193",  65.46, 20.99, False),
    ],
    "B": [
        ("PC-1193",  21.85, 16.27, False),
        ("PC-1191",  19.4,  18.1,  True),
        ("PC-1192",  21.3,  17.0,  True),
    ],
    "C": [
        ("PC-1193",  25.87, 12.99, False),
        ("PC-1191",  24.1,  13.7,  True),
        ("PC-1192",  26.4,  12.3,  True),
        ("PC-1194",  23.8,  14.0,  True),
        ("PC-1196",  27.2,  11.8,  True),
        ("PC-1198",  25.1,  13.2,  True),
    ],
}

fig, axes = plt.subplots(1, 3, figsize=(13, 4.2),
                          gridspec_kw={"width_ratios": [1, 3, 6]},
                          sharey=True)

for ax, (cfg, rows), title in zip(axes, cpu_data.items(),
                                   ["Config A (1 node)", "Config B (3 nodes)", "Config C (6 nodes)"]):
    labels    = [r[0] for r in rows]
    indexers  = [r[1] for r in rows]
    searches  = [r[2] for r in rows]
    estimated = [r[3] for r in rows]

    n     = len(rows)
    x     = np.arange(n)
    w     = 0.36

    for i, (idx_val, srch_val, est) in enumerate(zip(indexers, searches, estimated)):
        hatch = "///" if est else ""
        alpha = 0.72 if est else 1.0
        ax.bar(x[i] - w/2, idx_val, w, color=BLUE,   hatch=hatch, alpha=alpha,
               edgecolor="white", linewidth=0.6, zorder=3)
        ax.bar(x[i] + w/2, srch_val, w, color=ORANGE, hatch=hatch, alpha=alpha,
               edgecolor="white", linewidth=0.6, zorder=3)

        ax.text(x[i] - w/2, idx_val + 0.5, f"{idx_val:.1f}",
                ha="center", va="bottom", fontsize=7, color=BLUE)
        ax.text(x[i] + w/2, srch_val + 0.5, f"{srch_val:.1f}",
                ha="center", va="bottom", fontsize=7, color=ORANGE)

    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8.5)
    ax.set_title(title, fontsize=10)
    ax.tick_params(axis="x", length=0)
    ax.set_xlim(-0.6, n - 0.4)

axes[0].set_ylabel("CPU utilisation (%)")
fig.suptitle("Phase 3 — Container CPU utilisation per node", fontsize=11, y=1.01)

legend_patches = [
    mpatches.Patch(color=BLUE,   label="Indexer"),
    mpatches.Patch(color=ORANGE, label="Search"),
    mpatches.Patch(facecolor="white", edgecolor="gray",
                   hatch="///", label="Estimated (*)"),
]
fig.legend(handles=legend_patches, loc="upper right", framealpha=0.85,
           bbox_to_anchor=(1.0, 1.0), fontsize=9)

fig.tight_layout()
fig.savefig(OUT / "fig_cpu.pdf", bbox_inches="tight")
fig.savefig(OUT / "fig_cpu.png", bbox_inches="tight")
plt.close()
print("fig_cpu done")

# ══════════════════════════════════════════════════════════════════════════════
# Figure 6 — Memory usage per node
# ══════════════════════════════════════════════════════════════════════════════
mem_data = {
    "A": [
        ("PC-1193", 562.3, 616.2, False),
    ],
    "B": [
        ("PC-1193", 302.1, 278.9, False),
        ("PC-1191", 296.8, 281.5, True),
        ("PC-1192", 307.2, 274.6, True),
    ],
    "C": [
        ("PC-1193", 405.6, 360.5, False),
        ("PC-1191", 397.3, 354.8, True),
        ("PC-1192", 413.5, 361.2, True),
        ("PC-1194", 394.6, 369.7, True),
        ("PC-1196", 419.1, 348.4, True),
        ("PC-1198", 401.8, 357.6, True),
    ],
}

fig, axes = plt.subplots(1, 3, figsize=(13, 4.2),
                          gridspec_kw={"width_ratios": [1, 3, 6]},
                          sharey=True)

for ax, (cfg, rows), title in zip(axes, mem_data.items(),
                                   ["Config A (1 node)", "Config B (3 nodes)", "Config C (6 nodes)"]):
    labels    = [r[0] for r in rows]
    indexers  = [r[1] for r in rows]
    searches  = [r[2] for r in rows]
    estimated = [r[3] for r in rows]

    n = len(rows)
    x = np.arange(n)
    w = 0.36

    for i, (idx_val, srch_val, est) in enumerate(zip(indexers, searches, estimated)):
        hatch = "///" if est else ""
        alpha = 0.72 if est else 1.0
        ax.bar(x[i] - w/2, idx_val, w, color=BLUE,   hatch=hatch, alpha=alpha,
               edgecolor="white", linewidth=0.6, zorder=3)
        ax.bar(x[i] + w/2, srch_val, w, color=ORANGE, hatch=hatch, alpha=alpha,
               edgecolor="white", linewidth=0.6, zorder=3)

        ax.text(x[i] - w/2, idx_val + 4, f"{idx_val:.0f}",
                ha="center", va="bottom", fontsize=6.5, color=BLUE)
        ax.text(x[i] + w/2, srch_val + 4, f"{srch_val:.0f}",
                ha="center", va="bottom", fontsize=6.5, color=ORANGE)

    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8.5)
    ax.set_title(title, fontsize=10)
    ax.tick_params(axis="x", length=0)
    ax.set_xlim(-0.6, n - 0.4)

axes[0].set_ylabel("Memory usage (MiB)")
fig.suptitle("Phase 3 — Container memory usage per node", fontsize=11, y=1.01)

legend_patches = [
    mpatches.Patch(color=BLUE,   label="Indexer"),
    mpatches.Patch(color=ORANGE, label="Search"),
    mpatches.Patch(facecolor="white", edgecolor="gray",
                   hatch="///", label="Estimated (*)"),
]
fig.legend(handles=legend_patches, loc="upper right", framealpha=0.85,
           bbox_to_anchor=(1.0, 1.0), fontsize=9)

fig.tight_layout()
fig.savefig(OUT / "fig_memory.pdf", bbox_inches="tight")
fig.savefig(OUT / "fig_memory.png", bbox_inches="tight")
plt.close()
print("fig_memory done")

print("\nAll figures saved to:", OUT)
