#!/usr/bin/env python3
"""
Benchmark Plotter for TPC-C Evaluator

This script runs the Java TPC-C evaluator and generates plots for:
1. Warehouse scalability (throughput vs number of warehouses)
2. Contention impact (throughput vs contention level)
3. Overall scenario comparison
4. Multi-proposal comparison across all benchmarks

Usage:
    # Single proposal - run evaluator and plot
    python benchmark_plotter.py --input MyCC.java --output benchmark_results
    
    # Compare multiple proposals - run evaluator on each .java file
    python benchmark_plotter.py --proposals-dir ./proposals --output comparison_results
    
    # Compare multiple proposals - use existing JSON files (skip evaluator)
    python benchmark_plotter.py --proposals-dir ./proposals --skip-run --output comparison_results
    
    # Plot from existing JSON result files (no evaluator, no .java files needed)
    python benchmark_plotter.py --results-dir ./saved_json_results --output comparison_results
    
Arguments:
    --input         Path to a single CC implementation Java file
    --proposals-dir Directory containing multiple .java CC implementation files to compare
    --results-dir   Directory containing pre-computed JSON result files to plot from
    --output        Directory for output plots (default: benchmark_results)
    --skip-run      Skip running the evaluator, use existing JSON files
    --json-file     JSON output file from evaluator (for single file mode)

Modes:
    1. Single file mode (--input): Run evaluator on one Java file, generate plots
    2. Proposals mode (--proposals-dir): Run evaluator on all .java files, compare results
    3. Results mode (--results-dir): Load existing JSON files, compare and plot (fastest)
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Tuple, Optional
import glob

try:
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
    import numpy as np
except ImportError:
    print("Error: matplotlib and numpy are required. Install with: pip install matplotlib numpy")
    sys.exit(1)

# Plot styling
plt.style.use('seaborn-v0_8-whitegrid')

# Extended color palette for multiple proposals
PROPOSAL_COLORS = [
    '#2ecc71',  # Green
    '#3498db',  # Blue
    '#e74c3c',  # Red
    '#9b59b6',  # Purple
    '#f39c12',  # Orange
    '#1abc9c',  # Teal
    '#e91e63',  # Pink
    '#00bcd4',  # Cyan
    '#ff5722',  # Deep Orange
    '#795548',  # Brown
    '#607d8b',  # Blue Grey
    '#8bc34a',  # Light Green
]

COLORS = {
    'primary': '#2ecc71',      # Green
    'secondary': '#3498db',     # Blue
    'accent': '#e74c3c',        # Red
    'neutral': '#95a5a6',       # Gray
    'warehouse': ['#1abc9c', '#16a085', '#2ecc71', '#27ae60', '#3498db'],
    'contention': ['#2ecc71', '#f1c40f', '#e67e22', '#e74c3c', '#9b59b6', '#8e44ad'],
}


def run_evaluator(input_file: str, output_file: str = "output.json") -> dict:
    """Run the Java evaluator and return the results."""
    script_dir = Path(__file__).parent
    
    print(f"Running evaluator with input: {input_file}")
    print("-" * 50)
    
    # Run the shell script
    result = subprocess.run(
        ["bash", "run_evaluator.sh", input_file, output_file],
        cwd=script_dir,
        capture_output=True,
        text=True
    )
    
    if result.returncode != 0:
        print(f"Evaluator failed with code {result.returncode}")
        print(f"stdout: {result.stdout}")
        print(f"stderr: {result.stderr}")
        raise RuntimeError(f"Evaluator failed for {input_file}")
    
    print(result.stdout)
    
    # Load and return results
    output_path = script_dir / output_file
    with open(output_path) as f:
        return json.load(f)


def load_results(output_file: str = "output.json") -> dict:
    """Load results from an existing output file."""
    output_path = Path(output_file)
    
    if not output_path.exists():
        raise FileNotFoundError(f"Output file not found: {output_path}")
    
    with open(output_path) as f:
        return json.load(f)


def extract_scenario_data(results: dict) -> dict:
    """Extract and organize scenario data from results."""
    scenarios = results.get("artifacts", {}).get("scenario_summaries", {})
    
    data = {
        "basic": {},
        "warehouse_scalability": {},
        "contention_impact": {},
        "metrics": results.get("metrics", {}),
    }
    
    for name, info in scenarios.items():
        throughput = info.get("throughput", 0)
        is_serializable = info.get("is_serializable", False)
        committed = info.get("committed", 0)
        stalled = info.get("stalled_threads", 0)
        
        scenario_data = {
            "throughput": throughput,
            "is_serializable": is_serializable,
            "committed": committed,
            "stalled_threads": stalled,
            "avg_read_time_nanos": info.get("avg_read_time_nanos", 0),
            "avg_write_time_nanos": info.get("avg_write_time_nanos", 0),
            "avg_commit_time_nanos": info.get("avg_commit_time_nanos", 0),
        }
        
        if name.startswith("tpcc_wh"):
            # Extract warehouse count
            wh_count = int(name.replace("tpcc_wh", ""))
            data["warehouse_scalability"][wh_count] = scenario_data
        elif name.startswith("tpcc_contention_"):
            # Extract contention level
            level = name.replace("tpcc_contention_", "")
            data["contention_impact"][level] = scenario_data
        else:
            data["basic"][name] = scenario_data
    
    return data


def get_proposal_name(filepath: str) -> str:
    """Extract a clean proposal name from filepath."""
    name = Path(filepath).stem
    # Clean up common prefixes/suffixes
    for prefix in ["generated_cc_", "GeneratedCC_", "CC_", "cc_"]:
        if name.lower().startswith(prefix.lower()):
            name = name[len(prefix):]
    return name


def load_results_from_directory(results_dir: Path) -> Dict[str, dict]:
    """Load all JSON result files from a directory and extract scenario data."""
    json_files = list(results_dir.glob("*.json"))
    
    if not json_files:
        print(f"No .json files found in {results_dir}")
        return {}
    
    print(f"Found {len(json_files)} JSON result file(s):")
    for f in sorted(json_files):
        print(f"  - {f.name}")
    print()
    
    all_data = {}
    
    for json_file in sorted(json_files):
        proposal_name = get_proposal_name(str(json_file))
        
        try:
            with open(json_file) as f:
                results = json.load(f)
            
            # Validate that this looks like an evaluator output
            if "artifacts" not in results and "metrics" not in results:
                print(f"Warning: {json_file.name} doesn't appear to be a valid evaluator output, skipping")
                continue
            
            all_data[proposal_name] = extract_scenario_data(results)
            print(f"Loaded: {proposal_name} from {json_file.name}")
            
        except json.JSONDecodeError as e:
            print(f"Warning: Failed to parse {json_file.name}: {e}")
            continue
        except Exception as e:
            print(f"Warning: Error loading {json_file.name}: {e}")
            continue
    
    return all_data


def run_all_proposals(proposals_dir: Path, output_dir: Path, skip_run: bool = False) -> Dict[str, dict]:
    """Run evaluator on all proposals in directory and collect results."""
    script_dir = Path(__file__).parent
    
    # Find all Java files in the proposals directory
    java_files = list(proposals_dir.glob("*.java"))
    
    if not java_files:
        raise FileNotFoundError(f"No .java files found in {proposals_dir}")
    
    print(f"Found {len(java_files)} proposal(s) to evaluate:")
    for f in java_files:
        print(f"  - {f.name}")
    print()
    
    all_results = {}
    results_dir = output_dir / "json_results"
    results_dir.mkdir(parents=True, exist_ok=True)
    
    for java_file in sorted(java_files):
        proposal_name = get_proposal_name(str(java_file))
        json_output = results_dir / f"{proposal_name}.json"
        
        if skip_run and json_output.exists():
            print(f"Loading existing results for {proposal_name}...")
            results = load_results(str(json_output))
        else:
            print(f"\n{'='*60}")
            print(f"Evaluating: {proposal_name}")
            print(f"{'='*60}")
            try:
                results = run_evaluator(str(java_file), str(json_output))
            except RuntimeError as e:
                print(f"Warning: Failed to evaluate {proposal_name}: {e}")
                continue
        
        all_results[proposal_name] = extract_scenario_data(results)
    
    return all_results


# =============================================================================
# Single Proposal Plotting Functions
# =============================================================================

def plot_warehouse_scalability(data: dict, output_dir: Path):
    """Plot throughput vs number of warehouses for a single proposal."""
    wh_data = data["warehouse_scalability"]
    
    if not wh_data:
        print("No warehouse scalability data found, skipping plot")
        return
    
    wh_counts = sorted(wh_data.keys())
    throughputs = [wh_data[wh]["throughput"] for wh in wh_counts]
    serializable = [wh_data[wh]["is_serializable"] for wh in wh_counts]
    
    fig, ax = plt.subplots(figsize=(10, 6))
    
    bar_colors = [COLORS['primary'] if s else COLORS['accent'] for s in serializable]
    bars = ax.bar([str(wh) for wh in wh_counts], throughputs, color=bar_colors, edgecolor='white', linewidth=1.5)
    
    for bar, tp in zip(bars, throughputs):
        height = bar.get_height()
        ax.annotate(f'{tp:,.0f}',
                    xy=(bar.get_x() + bar.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha='center', va='bottom', fontsize=10, fontweight='bold')
    
    ax.set_xlabel('Number of Warehouses', fontsize=12, fontweight='bold')
    ax.set_ylabel('Throughput (txn/sec)', fontsize=12, fontweight='bold')
    ax.set_title('TPC-C Warehouse Scalability', fontsize=14, fontweight='bold')
    
    serializable_patch = mpatches.Patch(color=COLORS['primary'], label='Serializable')
    non_serializable_patch = mpatches.Patch(color=COLORS['accent'], label='Non-Serializable')
    ax.legend(handles=[serializable_patch, non_serializable_patch], loc='upper right')
    
    if len(throughputs) >= 2 and throughputs[0] > 0:
        scalability = throughputs[-1] / throughputs[0]
        ax.annotate(f'Scalability factor: {scalability:.2f}x\n({wh_counts[0]} → {wh_counts[-1]} warehouses)',
                    xy=(0.02, 0.98), xycoords='axes fraction',
                    fontsize=10, ha='left', va='top',
                    bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
    
    plt.tight_layout()
    plt.savefig(output_dir / 'warehouse_scalability.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'warehouse_scalability.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: warehouse_scalability.png/pdf")


def plot_contention_impact(data: dict, output_dir: Path):
    """Plot throughput vs contention level for a single proposal."""
    contention_data = data["contention_impact"]
    
    if not contention_data:
        print("No contention impact data found, skipping plot")
        return
    
    level_order = ["very_low", "low", "medium", "high", "very_high", "extreme"]
    levels = [l for l in level_order if l in contention_data]
    
    throughputs = [contention_data[l]["throughput"] for l in levels]
    serializable = [contention_data[l]["is_serializable"] for l in levels]
    
    display_names = {
        "very_low": "Very Low", "low": "Low", "medium": "Medium",
        "high": "High", "very_high": "Very High", "extreme": "Extreme"
    }
    level_labels = [display_names.get(l, l) for l in levels]
    
    fig, ax = plt.subplots(figsize=(12, 6))
    
    n_levels = len(levels)
    colors = [COLORS['contention'][i] if i < len(COLORS['contention']) else COLORS['accent'] 
              for i in range(n_levels)]
    
    bars = ax.bar(level_labels, throughputs, color=colors, edgecolor='white', linewidth=1.5)
    
    for i, (bar, is_ser) in enumerate(zip(bars, serializable)):
        if not is_ser:
            bar.set_hatch('//')
            bar.set_edgecolor('black')
    
    for bar, tp in zip(bars, throughputs):
        height = bar.get_height()
        ax.annotate(f'{tp:,.0f}',
                    xy=(bar.get_x() + bar.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha='center', va='bottom', fontsize=10, fontweight='bold')
    
    ax.set_xlabel('Contention Level', fontsize=12, fontweight='bold')
    ax.set_ylabel('Throughput (txn/sec)', fontsize=12, fontweight='bold')
    ax.set_title('TPC-C Contention Impact (8 Warehouses)', fontsize=14, fontweight='bold')
    
    if not all(serializable):
        ax.annotate('// = Non-Serializable',
                    xy=(0.98, 0.98), xycoords='axes fraction',
                    fontsize=10, ha='right', va='top',
                    bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.8))
    
    if len(throughputs) >= 2 and throughputs[0] > 0:
        degradation = (1 - throughputs[-1] / throughputs[0]) * 100
        ax.annotate(f'Throughput degradation: {degradation:.1f}%\n(very_low → extreme)',
                    xy=(0.02, 0.98), xycoords='axes fraction',
                    fontsize=10, ha='left', va='top',
                    bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
    
    plt.tight_layout()
    plt.savefig(output_dir / 'contention_impact.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'contention_impact.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: contention_impact.png/pdf")


def plot_all_scenarios(data: dict, output_dir: Path):
    """Plot comparison of all scenarios for a single proposal."""
    all_scenarios = {}
    
    for category in ["basic", "warehouse_scalability", "contention_impact"]:
        for name, info in data[category].items():
            if category == "warehouse_scalability":
                display_name = f"wh{name}"
            elif category == "contention_impact":
                display_name = f"cont_{name}"
            else:
                display_name = name
            all_scenarios[display_name] = info
    
    if not all_scenarios:
        print("No scenario data found, skipping plot")
        return
    
    sorted_scenarios = sorted(all_scenarios.items(), key=lambda x: x[1]["throughput"], reverse=True)
    names = [s[0] for s in sorted_scenarios]
    throughputs = [s[1]["throughput"] for s in sorted_scenarios]
    serializable = [s[1]["is_serializable"] for s in sorted_scenarios]
    
    fig, ax = plt.subplots(figsize=(14, 8))
    
    colors = []
    for name in names:
        if name.startswith("wh"):
            colors.append(COLORS['secondary'])
        elif name.startswith("cont_"):
            colors.append(COLORS['accent'])
        else:
            colors.append(COLORS['primary'])
    
    y_pos = range(len(names))
    bars = ax.barh(y_pos, throughputs, color=colors, edgecolor='white', linewidth=0.5)
    
    for i, (bar, is_ser) in enumerate(zip(bars, serializable)):
        if not is_ser:
            bar.set_hatch('//')
    
    for i, (bar, tp) in enumerate(zip(bars, throughputs)):
        width = bar.get_width()
        ax.annotate(f'{tp:,.0f}',
                    xy=(width, bar.get_y() + bar.get_height() / 2),
                    xytext=(5, 0),
                    textcoords="offset points",
                    ha='left', va='center', fontsize=9)
    
    ax.set_yticks(y_pos)
    ax.set_yticklabels(names)
    ax.set_xlabel('Throughput (txn/sec)', fontsize=12, fontweight='bold')
    ax.set_title('All Benchmark Scenarios - Throughput Comparison', fontsize=14, fontweight='bold')
    
    basic_patch = mpatches.Patch(color=COLORS['primary'], label='Basic Workloads')
    wh_patch = mpatches.Patch(color=COLORS['secondary'], label='Warehouse Scalability')
    cont_patch = mpatches.Patch(color=COLORS['accent'], label='Contention Levels')
    ax.legend(handles=[basic_patch, wh_patch, cont_patch], loc='lower right')
    
    plt.tight_layout()
    plt.savefig(output_dir / 'all_scenarios.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'all_scenarios.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: all_scenarios.png/pdf")


def plot_latency_breakdown(data: dict, output_dir: Path):
    """Plot latency breakdown for different scenarios."""
    wh_data = data["warehouse_scalability"]
    
    if not wh_data:
        print("No warehouse scalability data for latency plot, skipping")
        return
    
    wh_counts = sorted(wh_data.keys())
    
    read_times = [wh_data[wh]["avg_read_time_nanos"] / 1000 for wh in wh_counts]
    write_times = [wh_data[wh]["avg_write_time_nanos"] / 1000 for wh in wh_counts]
    commit_times = [wh_data[wh]["avg_commit_time_nanos"] / 1000 for wh in wh_counts]
    
    fig, ax = plt.subplots(figsize=(10, 6))
    
    x = range(len(wh_counts))
    width = 0.25
    
    ax.bar([i - width for i in x], read_times, width, label='Read', color=COLORS['primary'])
    ax.bar(x, write_times, width, label='Write', color=COLORS['secondary'])
    ax.bar([i + width for i in x], commit_times, width, label='Commit', color=COLORS['accent'])
    
    ax.set_xlabel('Number of Warehouses', fontsize=12, fontweight='bold')
    ax.set_ylabel('Average Latency (μs)', fontsize=12, fontweight='bold')
    ax.set_title('Operation Latency by Warehouse Count', fontsize=14, fontweight='bold')
    ax.set_xticks(x)
    ax.set_xticklabels([str(wh) for wh in wh_counts])
    ax.legend()
    ax.set_yscale('log')
    
    plt.tight_layout()
    plt.savefig(output_dir / 'latency_breakdown.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'latency_breakdown.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: latency_breakdown.png/pdf")


# =============================================================================
# Multi-Proposal Comparison Plotting Functions
# =============================================================================

def plot_compare_warehouse_scalability(all_data: Dict[str, dict], output_dir: Path):
    """Compare warehouse scalability across multiple proposals using line plot with markers."""
    proposals = list(all_data.keys())
    if not proposals:
        return
    
    # Get all warehouse counts from any proposal that has them
    all_wh_counts = set()
    for data in all_data.values():
        all_wh_counts.update(data["warehouse_scalability"].keys())
    
    if not all_wh_counts:
        print("No warehouse scalability data found across proposals, skipping comparison")
        return
    
    wh_counts = sorted(all_wh_counts)
    
    fig, ax = plt.subplots(figsize=(12, 7))
    
    # Markers for different proposals
    markers = ['o', 's', '^', 'D', 'v', 'p', 'h', '*', 'X', 'P', '<', '>']
    
    for i, proposal in enumerate(proposals):
        data = all_data[proposal]
        wh_data = data["warehouse_scalability"]
        
        throughputs = []
        serializable = []
        x_vals = []
        
        for wh in wh_counts:
            if wh in wh_data:
                throughputs.append(wh_data[wh]["throughput"])
                serializable.append(wh_data[wh]["is_serializable"])
                x_vals.append(wh)
        
        if not throughputs:
            continue
        
        color = PROPOSAL_COLORS[i % len(PROPOSAL_COLORS)]
        marker = markers[i % len(markers)]
        
        # Plot line with markers
        line, = ax.plot(x_vals, throughputs, marker=marker, markersize=10, 
                        linewidth=2.5, label=proposal, color=color)
        
        # Mark non-serializable points with red X
        for j, (x, y, is_ser) in enumerate(zip(x_vals, throughputs, serializable)):
            if not is_ser:
                ax.scatter([x], [y], marker='x', s=200, c='red', zorder=5, linewidths=3)
    
    ax.set_xlabel('Number of Warehouses', fontsize=12, fontweight='bold')
    ax.set_ylabel('Throughput (txn/sec)', fontsize=12, fontweight='bold')
    ax.set_title('Warehouse Scalability Comparison', fontsize=14, fontweight='bold')
    ax.set_xticks(wh_counts)
    ax.set_xticklabels([str(wh) for wh in wh_counts])
    ax.legend(loc='upper left', bbox_to_anchor=(1.02, 1))
    ax.grid(True, alpha=0.3)
    
    ax.annotate('Red X = Non-Serializable',
                xy=(0.98, 0.02), xycoords='axes fraction',
                fontsize=9, ha='right', va='bottom',
                bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.8))
    
    plt.tight_layout()
    plt.savefig(output_dir / 'compare_warehouse_scalability.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'compare_warehouse_scalability.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: compare_warehouse_scalability.png/pdf")


def plot_compare_contention_impact(all_data: Dict[str, dict], output_dir: Path):
    """Compare contention impact across multiple proposals using line plot with markers."""
    proposals = list(all_data.keys())
    if not proposals:
        return
    
    level_order = ["very_low", "low", "medium", "high", "very_high", "extreme"]
    display_names = {
        "very_low": "Very Low", "low": "Low", "medium": "Medium",
        "high": "High", "very_high": "Very High", "extreme": "Extreme"
    }
    
    # Get all contention levels that exist
    all_levels = set()
    for data in all_data.values():
        all_levels.update(data["contention_impact"].keys())
    
    if not all_levels:
        print("No contention impact data found across proposals, skipping comparison")
        return
    
    levels = [l for l in level_order if l in all_levels]
    level_labels = [display_names.get(l, l) for l in levels]
    
    fig, ax = plt.subplots(figsize=(14, 7))
    
    # Markers for different proposals
    markers = ['o', 's', '^', 'D', 'v', 'p', 'h', '*', 'X', 'P', '<', '>']
    
    x = np.arange(len(levels))
    
    for i, proposal in enumerate(proposals):
        data = all_data[proposal]
        cont_data = data["contention_impact"]
        
        throughputs = []
        serializable = []
        x_vals = []
        
        for j, level in enumerate(levels):
            if level in cont_data:
                throughputs.append(cont_data[level]["throughput"])
                serializable.append(cont_data[level]["is_serializable"])
                x_vals.append(j)
        
        if not throughputs:
            continue
        
        color = PROPOSAL_COLORS[i % len(PROPOSAL_COLORS)]
        marker = markers[i % len(markers)]
        
        # Plot line with markers
        line, = ax.plot(x_vals, throughputs, marker=marker, markersize=10, 
                        linewidth=2.5, label=proposal, color=color)
        
        # Mark non-serializable points with red X
        for j, (xv, y, is_ser) in enumerate(zip(x_vals, throughputs, serializable)):
            if not is_ser:
                ax.scatter([xv], [y], marker='x', s=200, c='red', zorder=5, linewidths=3)
    
    ax.set_xlabel('Contention Level', fontsize=12, fontweight='bold')
    ax.set_ylabel('Throughput (txn/sec)', fontsize=12, fontweight='bold')
    ax.set_title('Contention Impact Comparison (8 Warehouses)', fontsize=14, fontweight='bold')
    ax.set_xticks(x)
    ax.set_xticklabels(level_labels)
    ax.legend(loc='upper left', bbox_to_anchor=(1.02, 1))
    ax.grid(True, alpha=0.3)
    
    ax.annotate('Red X = Non-Serializable',
                xy=(0.98, 0.02), xycoords='axes fraction',
                fontsize=9, ha='right', va='bottom',
                bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.8))
    
    plt.tight_layout()
    plt.savefig(output_dir / 'compare_contention_impact.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'compare_contention_impact.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: compare_contention_impact.png/pdf")


def plot_compare_basic_workloads(all_data: Dict[str, dict], output_dir: Path):
    """Compare basic workloads across multiple proposals - one plot per workload."""
    proposals = list(all_data.keys())
    if not proposals:
        return
    
    # Get all basic workload names
    all_workloads = set()
    for data in all_data.values():
        all_workloads.update(data["basic"].keys())
    
    if not all_workloads:
        print("No basic workload data found across proposals, skipping comparison")
        return
    
    workloads = sorted(all_workloads)
    
    # Create a subdirectory for individual workload plots
    workload_dir = output_dir / "workload_plots"
    workload_dir.mkdir(exist_ok=True)
    
    # Create a separate plot for each workload
    for workload in workloads:
        fig, ax = plt.subplots(figsize=(max(10, len(proposals) * 0.8), 6))
        
        throughputs = []
        serializable = []
        proposal_names = []
        
        for proposal in proposals:
            data = all_data[proposal]
            basic_data = data["basic"]
            
            if workload in basic_data:
                throughputs.append(basic_data[workload]["throughput"])
                serializable.append(basic_data[workload]["is_serializable"])
                proposal_names.append(proposal)
        
        if not throughputs:
            plt.close()
            continue
        
        # Create bar chart
        colors = [PROPOSAL_COLORS[i % len(PROPOSAL_COLORS)] for i in range(len(proposal_names))]
        x = np.arange(len(proposal_names))
        bars = ax.bar(x, throughputs, color=colors, edgecolor='white', linewidth=1.5)
        
        # Mark non-serializable with hatching
        for bar, is_ser in zip(bars, serializable):
            if not is_ser:
                bar.set_hatch('//')
                bar.set_edgecolor('red')
        
        # Add value labels on bars
        for bar, tp in zip(bars, throughputs):
            height = bar.get_height()
            ax.annotate(f'{tp:,.0f}',
                        xy=(bar.get_x() + bar.get_width() / 2, height),
                        xytext=(0, 3),
                        textcoords="offset points",
                        ha='center', va='bottom', fontsize=10, fontweight='bold')
        
        ax.set_xlabel('Proposal', fontsize=12, fontweight='bold')
        ax.set_ylabel('Throughput (txn/sec)', fontsize=12, fontweight='bold')
        ax.set_title(f'{workload.upper()} Workload Comparison', fontsize=14, fontweight='bold')
        ax.set_xticks(x)
        ax.set_xticklabels(proposal_names, rotation=45, ha='right')
        
        if not all(serializable):
            ax.annotate('// = Non-Serializable',
                        xy=(0.98, 0.98), xycoords='axes fraction',
                        fontsize=9, ha='right', va='top',
                        bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.8))
        
        plt.tight_layout()
        plt.savefig(workload_dir / f'compare_{workload}.png', dpi=150, bbox_inches='tight')
        plt.savefig(workload_dir / f'compare_{workload}.pdf', bbox_inches='tight')
        plt.close()
        print(f"Saved: workload_plots/compare_{workload}.png/pdf")
    
    # Also create a summary figure with subplots for all workloads
    n_workloads = len(workloads)
    if n_workloads > 0:
        n_cols = min(2, n_workloads)
        n_rows = (n_workloads + n_cols - 1) // n_cols
        
        fig, axes = plt.subplots(n_rows, n_cols, figsize=(7 * n_cols, 5 * n_rows))
        if n_workloads == 1:
            axes = np.array([axes])
        axes = axes.flatten() if n_workloads > 1 else axes
        
        for idx, workload in enumerate(workloads):
            ax = axes[idx] if n_workloads > 1 else axes[0]
            
            throughputs = []
            serializable = []
            proposal_names = []
            
            for proposal in proposals:
                data = all_data[proposal]
                basic_data = data["basic"]
                
                if workload in basic_data:
                    throughputs.append(basic_data[workload]["throughput"])
                    serializable.append(basic_data[workload]["is_serializable"])
                    proposal_names.append(proposal)
            
            if not throughputs:
                ax.set_visible(False)
                continue
            
            colors = [PROPOSAL_COLORS[i % len(PROPOSAL_COLORS)] for i in range(len(proposal_names))]
            x = np.arange(len(proposal_names))
            bars = ax.bar(x, throughputs, color=colors, edgecolor='white')
            
            for bar, is_ser in zip(bars, serializable):
                if not is_ser:
                    bar.set_hatch('//')
            
            ax.set_ylabel('Throughput', fontsize=10)
            ax.set_title(f'{workload.upper()}', fontsize=12, fontweight='bold')
            ax.set_xticks(x)
            ax.set_xticklabels(proposal_names, rotation=45, ha='right', fontsize=8)
        
        # Hide empty subplots
        for idx in range(n_workloads, len(axes) if n_workloads > 1 else 1):
            if n_workloads > 1:
                axes[idx].set_visible(False)
        
        plt.suptitle('Basic Workloads Summary', fontsize=14, fontweight='bold', y=1.02)
        plt.tight_layout()
        plt.savefig(output_dir / 'compare_basic_workloads_summary.png', dpi=150, bbox_inches='tight')
        plt.savefig(output_dir / 'compare_basic_workloads_summary.pdf', bbox_inches='tight')
        plt.close()
        print(f"Saved: compare_basic_workloads_summary.png/pdf")


def plot_compare_overall_scores(all_data: Dict[str, dict], output_dir: Path):
    """Compare overall scores across multiple proposals."""
    proposals = list(all_data.keys())
    if not proposals:
        return
    
    fig, axes = plt.subplots(1, 3, figsize=(16, 6))
    
    # Metrics to compare
    metric_names = ['correctness', 'throughput_score', 'combined_score']
    metric_titles = ['Correctness', 'Throughput Score', 'Combined Score']
    
    for ax, metric, title in zip(axes, metric_names, metric_titles):
        values = []
        for proposal in proposals:
            metrics = all_data[proposal].get("metrics", {})
            values.append(metrics.get(metric, 0))
        
        colors = [PROPOSAL_COLORS[i % len(PROPOSAL_COLORS)] for i in range(len(proposals))]
        bars = ax.bar(proposals, values, color=colors, edgecolor='white')
        
        # Add value labels
        for bar, val in zip(bars, values):
            height = bar.get_height()
            ax.annotate(f'{val:.3f}',
                        xy=(bar.get_x() + bar.get_width() / 2, height),
                        xytext=(0, 3),
                        textcoords="offset points",
                        ha='center', va='bottom', fontsize=9, fontweight='bold')
        
        ax.set_ylabel(title, fontsize=11, fontweight='bold')
        ax.set_title(title, fontsize=12, fontweight='bold')
        ax.set_xticklabels(proposals, rotation=45, ha='right')
        
        if metric == 'correctness':
            ax.set_ylim(0, 1.1)
    
    plt.suptitle('Overall Performance Comparison', fontsize=14, fontweight='bold', y=1.02)
    plt.tight_layout()
    plt.savefig(output_dir / 'compare_overall_scores.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'compare_overall_scores.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: compare_overall_scores.png/pdf")


def plot_compare_heatmap(all_data: Dict[str, dict], output_dir: Path):
    """Create a heatmap comparing all proposals across all scenarios."""
    proposals = list(all_data.keys())
    if not proposals:
        return
    
    # Collect all scenarios
    all_scenarios = []
    
    # Add basic workloads
    for data in all_data.values():
        all_scenarios.extend(data["basic"].keys())
    
    # Add warehouse scenarios
    wh_counts = set()
    for data in all_data.values():
        wh_counts.update(data["warehouse_scalability"].keys())
    for wh in sorted(wh_counts):
        all_scenarios.append(f"wh{wh}")
    
    # Add contention scenarios
    level_order = ["very_low", "low", "medium", "high", "very_high", "extreme"]
    for level in level_order:
        for data in all_data.values():
            if level in data["contention_impact"]:
                all_scenarios.append(f"cont_{level}")
                break
    
    # Remove duplicates while preserving order
    seen = set()
    unique_scenarios = []
    for s in all_scenarios:
        if s not in seen:
            seen.add(s)
            unique_scenarios.append(s)
    all_scenarios = unique_scenarios
    
    if not all_scenarios:
        print("No scenario data for heatmap, skipping")
        return
    
    # Build throughput matrix
    throughput_matrix = []
    serializable_matrix = []
    
    for proposal in proposals:
        data = all_data[proposal]
        row = []
        ser_row = []
        
        for scenario in all_scenarios:
            throughput = 0
            is_ser = True
            
            if scenario in data["basic"]:
                throughput = data["basic"][scenario]["throughput"]
                is_ser = data["basic"][scenario]["is_serializable"]
            elif scenario.startswith("wh"):
                wh = int(scenario[2:])
                if wh in data["warehouse_scalability"]:
                    throughput = data["warehouse_scalability"][wh]["throughput"]
                    is_ser = data["warehouse_scalability"][wh]["is_serializable"]
            elif scenario.startswith("cont_"):
                level = scenario[5:]
                if level in data["contention_impact"]:
                    throughput = data["contention_impact"][level]["throughput"]
                    is_ser = data["contention_impact"][level]["is_serializable"]
            
            row.append(throughput)
            ser_row.append(is_ser)
        
        throughput_matrix.append(row)
        serializable_matrix.append(ser_row)
    
    throughput_matrix = np.array(throughput_matrix)
    
    # Normalize by column (scenario) for better visualization
    with np.errstate(divide='ignore', invalid='ignore'):
        normalized = throughput_matrix / throughput_matrix.max(axis=0, keepdims=True)
        normalized = np.nan_to_num(normalized, nan=0.0)
    
    fig, ax = plt.subplots(figsize=(max(16, len(all_scenarios) * 0.8), max(8, len(proposals) * 0.6)))
    
    im = ax.imshow(normalized, cmap='RdYlGn', aspect='auto', vmin=0, vmax=1)
    
    # Add text annotations
    for i in range(len(proposals)):
        for j in range(len(all_scenarios)):
            val = throughput_matrix[i, j]
            is_ser = serializable_matrix[i][j]
            text_color = 'white' if normalized[i, j] < 0.5 else 'black'
            marker = "" if is_ser else " ✗"
            ax.text(j, i, f'{val:,.0f}{marker}', ha='center', va='center', 
                    color=text_color, fontsize=8)
    
    ax.set_xticks(np.arange(len(all_scenarios)))
    ax.set_yticks(np.arange(len(proposals)))
    ax.set_xticklabels(all_scenarios, rotation=45, ha='right')
    ax.set_yticklabels(proposals)
    
    ax.set_title('Throughput Comparison Heatmap\n(Normalized by scenario, ✗ = Non-Serializable)', 
                 fontsize=14, fontweight='bold')
    
    cbar = plt.colorbar(im, ax=ax, shrink=0.8)
    cbar.set_label('Relative Performance', fontsize=11)
    
    plt.tight_layout()
    plt.savefig(output_dir / 'compare_heatmap.png', dpi=150, bbox_inches='tight')
    plt.savefig(output_dir / 'compare_heatmap.pdf', bbox_inches='tight')
    plt.close()
    print(f"Saved: compare_heatmap.png/pdf")


def generate_comparison_report(all_data: Dict[str, dict], output_dir: Path):
    """Generate a text comparison report."""
    proposals = list(all_data.keys())
    
    report_lines = []
    report_lines.append("=" * 80)
    report_lines.append("TPC-C Benchmark Comparison Report")
    report_lines.append(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    report_lines.append(f"Proposals compared: {len(proposals)}")
    report_lines.append("=" * 80)
    report_lines.append("")
    
    # Overall metrics comparison
    report_lines.append("OVERALL METRICS COMPARISON:")
    report_lines.append("-" * 80)
    header = f"{'Proposal':<20} {'Correctness':>12} {'Throughput':>12} {'Combined':>12}"
    report_lines.append(header)
    report_lines.append("-" * 80)
    
    for proposal in proposals:
        metrics = all_data[proposal].get("metrics", {})
        correctness = metrics.get("correctness", 0)
        throughput_score = metrics.get("throughput_score", 0)
        combined = metrics.get("combined_score", 0)
        report_lines.append(f"{proposal:<20} {correctness:>12.2%} {throughput_score:>12.4f} {combined:>12.4f}")
    
    report_lines.append("")
    
    # Find best performer for each scenario
    report_lines.append("BEST PERFORMERS BY SCENARIO:")
    report_lines.append("-" * 80)
    
    # Basic workloads
    all_workloads = set()
    for data in all_data.values():
        all_workloads.update(data["basic"].keys())
    
    for wl in sorted(all_workloads):
        best_proposal = None
        best_throughput = -1
        for proposal in proposals:
            if wl in all_data[proposal]["basic"]:
                tp = all_data[proposal]["basic"][wl]["throughput"]
                is_ser = all_data[proposal]["basic"][wl]["is_serializable"]
                if is_ser and tp > best_throughput:
                    best_throughput = tp
                    best_proposal = proposal
        if best_proposal:
            report_lines.append(f"  {wl:<20}: {best_proposal} ({best_throughput:,.0f} txn/sec)")
    
    # Warehouse scalability
    all_wh = set()
    for data in all_data.values():
        all_wh.update(data["warehouse_scalability"].keys())
    
    for wh in sorted(all_wh):
        best_proposal = None
        best_throughput = -1
        for proposal in proposals:
            if wh in all_data[proposal]["warehouse_scalability"]:
                tp = all_data[proposal]["warehouse_scalability"][wh]["throughput"]
                is_ser = all_data[proposal]["warehouse_scalability"][wh]["is_serializable"]
                if is_ser and tp > best_throughput:
                    best_throughput = tp
                    best_proposal = proposal
        if best_proposal:
            report_lines.append(f"  wh{wh:<18}: {best_proposal} ({best_throughput:,.0f} txn/sec)")
    
    # Contention levels
    level_order = ["very_low", "low", "medium", "high", "very_high", "extreme"]
    for level in level_order:
        best_proposal = None
        best_throughput = -1
        for proposal in proposals:
            if level in all_data[proposal]["contention_impact"]:
                tp = all_data[proposal]["contention_impact"][level]["throughput"]
                is_ser = all_data[proposal]["contention_impact"][level]["is_serializable"]
                if is_ser and tp > best_throughput:
                    best_throughput = tp
                    best_proposal = proposal
        if best_proposal:
            report_lines.append(f"  cont_{level:<14}: {best_proposal} ({best_throughput:,.0f} txn/sec)")
    
    report_lines.append("")
    report_lines.append("=" * 80)
    
    report_text = "\n".join(report_lines)
    
    report_path = output_dir / "comparison_report.txt"
    with open(report_path, "w") as f:
        f.write(report_text)
    
    print(f"Saved: comparison_report.txt")
    print("\n" + report_text)


def generate_summary_report(data: dict, results: dict, output_dir: Path):
    """Generate a text summary report for single proposal."""
    report_lines = []
    report_lines.append("=" * 60)
    report_lines.append("TPC-C Benchmark Summary Report")
    report_lines.append(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    report_lines.append("=" * 60)
    report_lines.append("")
    
    metrics = results.get("metrics", {})
    report_lines.append("OVERALL METRICS:")
    report_lines.append(f"  Correctness:      {metrics.get('correctness', 0):.2%}")
    report_lines.append(f"  Throughput Score: {metrics.get('throughput_score', 0):.4f}")
    report_lines.append(f"  Combined Score:   {metrics.get('combined_score', 0):.4f}")
    report_lines.append("")
    
    wh_data = data["warehouse_scalability"]
    if wh_data:
        report_lines.append("WAREHOUSE SCALABILITY:")
        for wh in sorted(wh_data.keys()):
            info = wh_data[wh]
            status = "✓" if info["is_serializable"] else "✗"
            report_lines.append(f"  {wh:2d} warehouses: {info['throughput']:>10,.1f} txn/sec [{status}]")
        
        wh_counts = sorted(wh_data.keys())
        if len(wh_counts) >= 2 and wh_data[wh_counts[0]]["throughput"] > 0:
            scalability = wh_data[wh_counts[-1]]["throughput"] / wh_data[wh_counts[0]]["throughput"]
            report_lines.append(f"  Scalability factor: {scalability:.2f}x")
        report_lines.append("")
    
    cont_data = data["contention_impact"]
    if cont_data:
        report_lines.append("CONTENTION IMPACT (8 warehouses):")
        level_order = ["very_low", "low", "medium", "high", "very_high", "extreme"]
        for level in level_order:
            if level in cont_data:
                info = cont_data[level]
                status = "✓" if info["is_serializable"] else "✗"
                report_lines.append(f"  {level:12s}: {info['throughput']:>10,.1f} txn/sec [{status}]")
        
        if "very_low" in cont_data and "extreme" in cont_data:
            if cont_data["very_low"]["throughput"] > 0:
                degradation = (1 - cont_data["extreme"]["throughput"] / cont_data["very_low"]["throughput"]) * 100
                report_lines.append(f"  Throughput degradation: {degradation:.1f}%")
        report_lines.append("")
    
    basic_data = data["basic"]
    if basic_data:
        report_lines.append("BASIC WORKLOADS:")
        for name, info in basic_data.items():
            status = "✓" if info["is_serializable"] else "✗"
            report_lines.append(f"  {name:12s}: {info['throughput']:>10,.1f} txn/sec [{status}]")
        report_lines.append("")
    
    report_lines.append("=" * 60)
    
    report_text = "\n".join(report_lines)
    
    report_path = output_dir / "benchmark_report.txt"
    with open(report_path, "w") as f:
        f.write(report_text)
    
    print(f"Saved: benchmark_report.txt")
    print("\n" + report_text)


def main():
    parser = argparse.ArgumentParser(
        description="Run TPC-C benchmark and generate comparison plots",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Single proposal
  python benchmark_plotter.py --input MyCC.java
  
  # Compare multiple proposals (run evaluator on each)
  python benchmark_plotter.py --proposals-dir ./previous_proposals/archive1
  
  # Use existing results (skip running evaluator)  
  python benchmark_plotter.py --proposals-dir ./proposals --skip-run
  
  # Load from a folder of existing JSON result files (no evaluator needed)
  python benchmark_plotter.py --results-dir ./saved_results
        """
    )
    parser.add_argument("--input", 
                        help="Path to a single CC implementation Java file")
    parser.add_argument("--proposals-dir",
                        help="Directory containing multiple .java CC implementations to compare")
    parser.add_argument("--results-dir",
                        help="Directory containing pre-computed JSON result files to plot from")
    parser.add_argument("--output", default="benchmark_results",
                        help="Directory for output plots (default: benchmark_results)")
    parser.add_argument("--skip-run", action="store_true",
                        help="Skip running evaluator, use existing JSON files")
    parser.add_argument("--json-file", default="output.json",
                        help="JSON output file from evaluator (for single file mode)")
    
    args = parser.parse_args()
    
    # Validate arguments - need exactly one of: --input, --proposals-dir, or --results-dir
    mode_count = sum([args.input is not None, args.proposals_dir is not None, args.results_dir is not None])
    if mode_count == 0:
        parser.error("One of --input, --proposals-dir, or --results-dir must be specified")
    if mode_count > 1:
        parser.error("Cannot specify multiple of --input, --proposals-dir, and --results-dir")
    
    script_dir = Path(__file__).parent
    output_dir = script_dir / args.output
    output_dir.mkdir(parents=True, exist_ok=True)
    
    if args.results_dir:
        # Load from existing JSON results directory
        results_dir = Path(args.results_dir)
        if not results_dir.is_absolute():
            results_dir = script_dir / results_dir
        
        if not results_dir.exists():
            print(f"Error: Results directory not found: {results_dir}")
            sys.exit(1)
        
        print(f"Loading results from JSON files mode")
        print(f"Results directory: {results_dir}")
        print(f"Output directory: {output_dir}")
        print("=" * 60)
        
        # Load all JSON files from the directory
        all_data = load_results_from_directory(results_dir)
        
        if not all_data:
            print("No valid JSON results found. Exiting.")
            sys.exit(1)
        
        # Generate comparison plots
        print("\nGenerating comparison plots...")
        plot_compare_warehouse_scalability(all_data, output_dir)
        plot_compare_contention_impact(all_data, output_dir)
        plot_compare_basic_workloads(all_data, output_dir)
        plot_compare_overall_scores(all_data, output_dir)
        plot_compare_heatmap(all_data, output_dir)
        
        # Generate comparison report
        print("\nGenerating comparison report...")
        generate_comparison_report(all_data, output_dir)
        
    elif args.proposals_dir:
        # Multi-proposal comparison mode
        proposals_dir = Path(args.proposals_dir)
        if not proposals_dir.is_absolute():
            proposals_dir = script_dir / proposals_dir
        
        if not proposals_dir.exists():
            print(f"Error: Proposals directory not found: {proposals_dir}")
            sys.exit(1)
        
        print(f"Multi-proposal comparison mode")
        print(f"Proposals directory: {proposals_dir}")
        print(f"Output directory: {output_dir}")
        print("=" * 60)
        
        # Run all proposals
        all_data = run_all_proposals(proposals_dir, output_dir, args.skip_run)
        
        if not all_data:
            print("No valid results collected. Exiting.")
            sys.exit(1)
        
        # Generate comparison plots
        print("\nGenerating comparison plots...")
        plot_compare_warehouse_scalability(all_data, output_dir)
        plot_compare_contention_impact(all_data, output_dir)
        plot_compare_basic_workloads(all_data, output_dir)
        plot_compare_overall_scores(all_data, output_dir)
        plot_compare_heatmap(all_data, output_dir)
        
        # Generate comparison report
        print("\nGenerating comparison report...")
        generate_comparison_report(all_data, output_dir)
        
    else:
        # Single proposal mode
        print(f"Single proposal mode")
        print(f"Input file: {args.input}")
        print(f"Output directory: {output_dir}")
        print("=" * 60)
        
        if args.skip_run:
            print(f"Loading existing results from {args.json_file}...")
            results = load_results(args.json_file)
        else:
            print(f"Running evaluator with {args.input}...")
            results = run_evaluator(args.input, args.json_file)
        
        print("\nProcessing results...")
        data = extract_scenario_data(results)
        
        # Save JSON results to output directory
        proposal_name = get_proposal_name(args.input)
        json_output_path = output_dir / f"{proposal_name}_results.json"
        with open(json_output_path, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"Saved JSON results to: {json_output_path}")
        
        print("\nGenerating plots...")
        plot_warehouse_scalability(data, output_dir)
        plot_contention_impact(data, output_dir)
        plot_all_scenarios(data, output_dir)
        plot_latency_breakdown(data, output_dir)
        
        print("\nGenerating summary report...")
        generate_summary_report(data, results, output_dir)
    
    print(f"\nAll outputs saved to: {output_dir}")


if __name__ == "__main__":
    main()
