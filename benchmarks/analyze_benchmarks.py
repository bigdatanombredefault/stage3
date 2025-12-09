import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import numpy as np
from pathlib import Path
import sys

# Set style
sns.set_style("whitegrid")
plt.rcParams['figure.figsize'] = (12, 6)
plt.rcParams['font.size'] = 10

def load_results(csv_file):
    """Load JMH CSV results"""
    try:
        df = pd.read_csv(csv_file)
        print(f"✓ Loaded {len(df)} benchmark results from {csv_file}")
        return df
    except FileNotFoundError:
        print(f"✗ File not found: {csv_file}")
        sys.exit(1)
    except Exception as e:
        print(f"✗ Error loading file: {e}")
        sys.exit(1)

def extract_benchmark_name(full_name):
    """Extract clean benchmark name from full class.method name"""
    if '.' in full_name:
        return full_name.split('.')[-1]
    return full_name

def create_output_dir():
    """Create output directory for plots"""
    output_dir = Path("benchmark-results")
    output_dir.mkdir(exist_ok=True)
    return output_dir

def plot_operation_comparison(df, output_dir):
    """Plot 1: Compare average time across different operations"""
    print("\n📊 Generating operation comparison chart...")

    df['BenchmarkName'] = df['Benchmark'].apply(extract_benchmark_name)

    # Group by benchmark (average across all parameters)
    summary = df.groupby('BenchmarkName').agg({
        'Score': ['mean', 'std']
    }).round(3)

    summary.columns = ['Mean', 'StdDev']
    summary = summary.sort_values('Mean', ascending=True)

    # Plot
    fig, ax = plt.subplots(figsize=(14, 8))
    y_pos = np.arange(len(summary))

    ax.barh(y_pos, summary['Mean'], xerr=summary['StdDev'],
            capsize=5, alpha=0.8, color='steelblue')
    ax.set_yticks(y_pos)
    ax.set_yticklabels(summary.index, fontsize=9)
    ax.set_xlabel('Average Time (ms)')
    ax.set_title('Benchmark Operations Comparison\n(Lower is Better)',
                 fontsize=14, fontweight='bold')
    ax.grid(axis='x', alpha=0.3)

    plt.tight_layout()
    plt.savefig(output_dir / 'operation_comparison.png', dpi=300)
    print(f"  ✓ Saved: operation_comparison.png")
    plt.close()

def plot_scalability_analysis(df, output_dir):
    """Plot 2: Scalability - how performance changes with dataset size"""
    print("\n📊 Generating scalability analysis...")

    # Check if we have parameter data
    param_cols = [col for col in df.columns if 'Param' in col]
    if not param_cols:
        print("  ⚠ No parameter data found, skipping scalability plot")
        return

    param_col = param_cols[0]  # Use first parameter column

    df['BenchmarkName'] = df['Benchmark'].apply(extract_benchmark_name)
    df[param_col] = pd.to_numeric(df[param_col], errors='coerce')

    # Select key benchmarks for scalability
    key_benchmarks = [
        'completeIndexingPipeline',
        'buildInMemoryInvertedIndex',
        'selectAllBooks',
        'fullSearchPipeline'
    ]

    scalability_df = df[df['BenchmarkName'].isin(key_benchmarks)]

    if scalability_df.empty:
        print("  ⚠ No scalability benchmarks found")
        return

    # Plot
    fig, ax = plt.subplots(figsize=(12, 7))

    for benchmark in key_benchmarks:
        data = scalability_df[scalability_df['BenchmarkName'] == benchmark]
        if not data.empty:
            data = data.sort_values(param_col)
            ax.plot(data[param_col], data['Score'], marker='o',
                    linewidth=2, markersize=8, label=benchmark, alpha=0.8)

    ax.set_xlabel('Dataset Size (number of books)', fontsize=12)
    ax.set_ylabel('Time (ms)', fontsize=12)
    ax.set_title('Scalability Analysis\nHow Performance Changes with Dataset Size',
                 fontsize=14, fontweight='bold')
    ax.legend(bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=9)
    ax.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(output_dir / 'scalability_analysis.png', dpi=300, bbox_inches='tight')
    print(f"  ✓ Saved: scalability_analysis.png")
    plt.close()

def plot_component_breakdown(df, output_dir):
    """Plot 3: Breakdown by component (Database, Index, Search)"""
    print("\n📊 Generating component breakdown...")

    df['BenchmarkName'] = df['Benchmark'].apply(extract_benchmark_name)

    # Categorize benchmarks
    def categorize(name):
        name_lower = name.lower()
        if any(x in name_lower for x in ['database', 'insert', 'select', 'query', 'count']):
            return 'Database'
        elif any(x in name_lower for x in ['index', 'serialize', 'deserialize', 'word']):
            return 'Index Operations'
        elif any(x in name_lower for x in ['search', 'filter', 'rank']):
            return 'Search Service'
        elif any(x in name_lower for x in ['tokenize', 'extract', 'metadata']):
            return 'Data Processing'
        else:
            return 'End-to-End'

    df['Component'] = df['BenchmarkName'].apply(categorize)

    # Calculate average time per component
    component_avg = df.groupby('Component')['Score'].agg(['mean', 'std']).round(3)
    component_avg = component_avg.sort_values('mean')

    # Plot
    fig, ax = plt.subplots(figsize=(10, 6))
    colors = sns.color_palette("husl", len(component_avg))

    ax.barh(component_avg.index, component_avg['mean'],
            xerr=component_avg['std'], capsize=5,
            color=colors, alpha=0.8)
    ax.set_xlabel('Average Time (ms)', fontsize=12)
    ax.set_title('Performance by Component\n(Lower is Better)',
                 fontsize=14, fontweight='bold')
    ax.grid(axis='x', alpha=0.3)

    plt.tight_layout()
    plt.savefig(output_dir / 'component_breakdown.png', dpi=300)
    print(f"  ✓ Saved: component_breakdown.png")
    plt.close()

def plot_database_scaling(df, output_dir):
    """Plot 4: Database operations scaling"""
    print("\n📊 Generating database scaling analysis...")

    df['BenchmarkName'] = df['Benchmark'].apply(extract_benchmark_name)

    # Filter database benchmarks
    db_benchmarks = df[df['Benchmark'].str.contains('DatabaseBenchmark', na=False)]

    if db_benchmarks.empty:
        print("  ⚠ No database benchmarks found")
        return

    param_cols = [col for col in db_benchmarks.columns if 'Param' in col]
    if not param_cols:
        print("  ⚠ No parameter data for database benchmarks")
        return

    param_col = param_cols[0]
    db_benchmarks[param_col] = pd.to_numeric(db_benchmarks[param_col], errors='coerce')
    db_benchmarks['BenchmarkName'] = db_benchmarks['Benchmark'].apply(extract_benchmark_name)

    # Plot
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

    # Subplot 1: Read operations
    read_ops = ['selectBookById', 'selectAllBooks', 'countAllBooks']
    for op in read_ops:
        data = db_benchmarks[db_benchmarks['BenchmarkName'] == op]
        if not data.empty:
            data = data.sort_values(param_col)
            ax1.plot(data[param_col], data['Score'], marker='o',
                     linewidth=2, markersize=8, label=op, alpha=0.8)

    ax1.set_xlabel('Database Size (books)', fontsize=11)
    ax1.set_ylabel('Time (ms)', fontsize=11)
    ax1.set_title('Database Read Operations', fontsize=12, fontweight='bold')
    ax1.legend(fontsize=9)
    ax1.grid(True, alpha=0.3)

    # Subplot 2: Write operations
    write_ops = ['insertBookMetadata']
    for op in write_ops:
        data = db_benchmarks[db_benchmarks['BenchmarkName'] == op]
        if not data.empty:
            data = data.sort_values(param_col)
            ax2.plot(data[param_col], data['Score'], marker='s',
                     linewidth=2, markersize=8, label=op, alpha=0.8, color='orange')

    ax2.set_xlabel('Database Size (books)', fontsize=11)
    ax2.set_ylabel('Time (ms)', fontsize=11)
    ax2.set_title('Database Write Operations', fontsize=12, fontweight='bold')
    ax2.legend(fontsize=9)
    ax2.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(output_dir / 'database_scaling.png', dpi=300)
    print(f"  ✓ Saved: database_scaling.png")
    plt.close()

def plot_throughput_analysis(df, output_dir):
    """Plot 5: Throughput visualization (if available)"""
    print("\n📊 Generating throughput analysis...")

    throughput_df = df[df['Mode'] == 'thrpt']

    if throughput_df.empty:
        print("  ⚠ No throughput benchmarks found")
        return

    throughput_df['BenchmarkName'] = throughput_df['Benchmark'].apply(extract_benchmark_name)

    # Plot
    fig, ax = plt.subplots(figsize=(10, 6))

    summary = throughput_df.groupby('BenchmarkName')['Score'].agg(['mean', 'std']).round(3)
    summary = summary.sort_values('mean', ascending=False)

    ax.bar(range(len(summary)), summary['mean'], yerr=summary['std'],
           capsize=5, alpha=0.8, color='green')
    ax.set_xticks(range(len(summary)))
    ax.set_xticklabels(summary.index, rotation=45, ha='right')
    ax.set_ylabel('Operations per Second', fontsize=12)
    ax.set_title('Throughput Analysis\n(Higher is Better)',
                 fontsize=14, fontweight='bold')
    ax.grid(axis='y', alpha=0.3)

    plt.tight_layout()
    plt.savefig(output_dir / 'throughput_analysis.png', dpi=300)
    print(f"  ✓ Saved: throughput_analysis.png")
    plt.close()

def generate_summary_table(df, output_dir):
    """Generate summary statistics table"""
    print("\n📋 Generating summary statistics...")

    df['BenchmarkName'] = df['Benchmark'].apply(extract_benchmark_name)

    summary = df.groupby('BenchmarkName').agg({
        'Score': ['mean', 'std', 'min', 'max', 'count']
    }).round(3)

    summary.columns = ['Mean (ms)', 'Std Dev', 'Min (ms)', 'Max (ms)', 'Iterations']
    summary = summary.sort_values('Mean (ms)')

    # Save to CSV
    summary.to_csv(output_dir / 'summary_statistics.csv')
    print(f"  ✓ Saved: summary_statistics.csv")

    # Print top and bottom performers
    print("\n🏆 Top 5 Fastest Operations:")
    print(summary.head()['Mean (ms)'].to_string())

    print("\n⏱️  Top 5 Slowest Operations:")
    print(summary.tail()['Mean (ms)'].to_string())

    return summary

def main():
    print("=" * 60)
    print("  JMH Benchmark Results Analysis")
    print("=" * 60)

    # Check for CSV file
    csv_file = 'results.csv'
    if len(sys.argv) > 1:
        csv_file = sys.argv[1]

    # Load data
    df = load_results(csv_file)

    # Create output directory
    output_dir = create_output_dir()
    print(f"\n📁 Output directory: {output_dir}")

    # Generate all visualizations
    plot_operation_comparison(df, output_dir)
    plot_scalability_analysis(df, output_dir)
    plot_component_breakdown(df, output_dir)
    plot_database_scaling(df, output_dir)
    plot_throughput_analysis(df, output_dir)

    # Generate summary
    summary = generate_summary_table(df, output_dir)

    print("\n" + "=" * 60)
    print("  ✅ Analysis complete!")
    print(f"  📊 Generated {len(list(output_dir.glob('*.png')))} plots")
    print(f"  📁 Results saved in: {output_dir}")
    print("=" * 60)

if __name__ == '__main__':
    main()
