#!/usr/bin/env python3
"""
Analyze collected gesture data to verify if cumulative gyro X threshold (50 rad/s)
can distinguish between flexion and rotation gestures.
"""

import sys
import os
import pandas as pd
import glob

def analyze_csv_file(file_path, gesture_type):
    """Analyze a single CSV file and calculate cumulative gyro X for each gesture."""
    try:
        df = pd.read_csv(file_path)
        
        # Filter out baseline data (gesture_number == 0)
        gesture_data = df[df['gesture_number'] > 0].copy()
        
        if gesture_data.empty:
            print(f"  No gesture data found in {os.path.basename(file_path)}")
            return []
        
        results = []
        
        # Group by gesture number
        for gesture_num in sorted(gesture_data['gesture_number'].unique()):
            gesture_samples = gesture_data[gesture_data['gesture_number'] == gesture_num]
            
            if len(gesture_samples) < 10:  # Skip if too few samples
                continue
            
            # Calculate cumulative absolute gyro X (simulating 0.5 second analysis at 50Hz = 25 samples)
            # But we'll use all samples in the gesture window (2 seconds = ~100 samples at 50Hz)
            # For comparison, we'll calculate cumulative over 0.5s window (25 samples)
            gyro_x_abs = gesture_samples['gyro_x'].abs()
            
            # Calculate cumulative for first 0.5 seconds (25 samples at 50Hz)
            samples_05s = min(25, len(gyro_x_abs))
            cumulative_gyro_x_05s = gyro_x_abs.head(samples_05s).sum()
            
            # Also calculate for full gesture window for reference
            cumulative_gyro_x_full = gyro_x_abs.sum()
            
            # Calculate average
            avg_gyro_x = gyro_x_abs.mean()
            peak_gyro_x = gyro_x_abs.max()
            
            results.append({
                'file': os.path.basename(file_path),
                'gesture_num': gesture_num,
                'cumulative_gyro_x_05s': cumulative_gyro_x_05s,
                'cumulative_gyro_x_full': cumulative_gyro_x_full,
                'avg_gyro_x': avg_gyro_x,
                'peak_gyro_x': peak_gyro_x,
                'samples': len(gesture_samples)
            })
        
        return results
    except Exception as e:
        print(f"  Error analyzing {file_path}: {e}")
        return []

def main():
    # Find all CSV files
    flexion_files = glob.glob('gesture_data/flexion/*.csv')
    rotation_files = glob.glob('gesture_data/rotation/*.csv')
    
    print("=" * 80)
    print("Gesture Threshold Analysis")
    print("=" * 80)
    print(f"\nThreshold: 50 rad/s cumulative gyro X (over 0.5 seconds = 25 samples at 50Hz)")
    print(f"\nAnalyzing {len(flexion_files)} flexion file(s) and {len(rotation_files)} rotation file(s)...\n")
    
    flexion_results = []
    rotation_results = []
    
    # Analyze flexion files
    print("FLEXION DATA:")
    print("-" * 80)
    for file_path in flexion_files:
        print(f"\nAnalyzing: {os.path.basename(file_path)}")
        results = analyze_csv_file(file_path, 'flexion')
        flexion_results.extend(results)
        for r in results:
            print(f"  Gesture {r['gesture_num']:2d}: Cumulative (0.5s) = {r['cumulative_gyro_x_05s']:7.2f} rad/s, "
                  f"Avg = {r['avg_gyro_x']:6.3f}, Peak = {r['peak_gyro_x']:6.3f}")
    
    # Analyze rotation files
    print("\n\nROTATION DATA:")
    print("-" * 80)
    for file_path in rotation_files:
        print(f"\nAnalyzing: {os.path.basename(file_path)}")
        results = analyze_csv_file(file_path, 'rotation')
        rotation_results.extend(results)
        for r in results:
            print(f"  Gesture {r['gesture_num']:2d}: Cumulative (0.5s) = {r['cumulative_gyro_x_05s']:7.2f} rad/s, "
                  f"Avg = {r['avg_gyro_x']:6.3f}, Peak = {r['peak_gyro_x']:6.3f}")
    
    # Summary statistics
    print("\n\n" + "=" * 80)
    print("SUMMARY STATISTICS")
    print("=" * 80)
    
    if flexion_results:
        flexion_cumulative = [r['cumulative_gyro_x_05s'] for r in flexion_results]
        print(f"\nFLEXION (n={len(flexion_results)} gestures):")
        print(f"  Cumulative Gyro X (0.5s):")
        print(f"    Min:  {min(flexion_cumulative):7.2f} rad/s")
        print(f"    Max:  {max(flexion_cumulative):7.2f} rad/s")
        print(f"    Mean: {sum(flexion_cumulative)/len(flexion_cumulative):7.2f} rad/s")
        print(f"    All below threshold (50 rad/s): {all(x < 50 for x in flexion_cumulative)}")
    
    if rotation_results:
        rotation_cumulative = [r['cumulative_gyro_x_05s'] for r in rotation_results]
        print(f"\nROTATION (n={len(rotation_results)} gestures):")
        print(f"  Cumulative Gyro X (0.5s):")
        print(f"    Min:  {min(rotation_cumulative):7.2f} rad/s")
        print(f"    Max:  {max(rotation_cumulative):7.2f} rad/s")
        print(f"    Mean: {sum(rotation_cumulative)/len(rotation_cumulative):7.2f} rad/s")
        print(f"    All above threshold (50 rad/s): {all(x > 50 for x in rotation_cumulative)}")
    
    # Threshold analysis
    print("\n" + "=" * 80)
    print("THRESHOLD ANALYSIS (50 rad/s)")
    print("=" * 80)
    
    if flexion_results and rotation_results:
        flexion_cumulative = [r['cumulative_gyro_x_05s'] for r in flexion_results]
        rotation_cumulative = [r['cumulative_gyro_x_05s'] for r in rotation_results]
        
        flexion_max = max(flexion_cumulative)
        rotation_min = min(rotation_cumulative)
        
        print(f"\nFlexion max cumulative gyro X: {flexion_max:.2f} rad/s")
        print(f"Rotation min cumulative gyro X: {rotation_min:.2f} rad/s")
        print(f"Threshold: 50.00 rad/s")
        print(f"\nSeparation margin: {rotation_min - flexion_max:.2f} rad/s")
        print(f"Safety factor: {rotation_min / flexion_max:.2f}x" if flexion_max > 0 else "N/A")
        
        # Check if threshold works
        flexion_below = sum(1 for x in flexion_cumulative if x < 50)
        rotation_above = sum(1 for x in rotation_cumulative if x > 50)
        
        print(f"\n✓ Flexion gestures below threshold: {flexion_below}/{len(flexion_cumulative)}")
        print(f"✓ Rotation gestures above threshold: {rotation_above}/{len(rotation_cumulative)}")
        
        if flexion_below == len(flexion_cumulative) and rotation_above == len(rotation_cumulative):
            print(f"\n✅ THRESHOLD WORKS: Perfect separation!")
        elif flexion_below == len(flexion_cumulative):
            print(f"\n⚠️  WARNING: Some rotation gestures below threshold")
        elif rotation_above == len(rotation_cumulative):
            print(f"\n⚠️  WARNING: Some flexion gestures above threshold")
        else:
            print(f"\n❌ THRESHOLD DOES NOT WORK: Overlap detected")
    
    print("\n" + "=" * 80)

if __name__ == "__main__":
    # Change to project root if running from scripts directory
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    os.chdir(project_root)
    
    main()



