#!/usr/bin/env python3
"""
Visualize sensor data from CSV file in interactive browser graph.
Usage: python visualize_sensor_data.py <csv_file_path>
"""

import sys
import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots
import webbrowser
import tempfile
import os

def load_and_validate_csv(file_path):
    """Load CSV file and validate structure."""
    try:
        df = pd.read_csv(file_path)
        
        required_columns = ['timestamp_ms', 'accel_x', 'accel_y', 'accel_z', 
                           'gyro_x', 'gyro_y', 'gyro_z', 'gesture_type', 'gesture_number']
        
        missing = [col for col in required_columns if col not in df.columns]
        if missing:
            print(f"Error: Missing required columns: {missing}")
            sys.exit(1)
        
        return df
    except FileNotFoundError:
        print(f"Error: File not found: {file_path}")
        sys.exit(1)
    except Exception as e:
        print(f"Error loading CSV: {e}")
        sys.exit(1)

def calculate_statistics(df):
    """Calculate statistics for baseline and each gesture."""
    stats = {}
    
    # Baseline statistics
    baseline = df[df['gesture_number'] == 0]
    if not baseline.empty:
        stats['baseline'] = {
            'accel_x': {'mean': baseline['accel_x'].mean(), 'std': baseline['accel_x'].std()},
            'accel_y': {'mean': baseline['accel_y'].mean(), 'std': baseline['accel_y'].std()},
            'accel_z': {'mean': baseline['accel_z'].mean(), 'std': baseline['accel_z'].std()},
            'gyro_x': {'mean': baseline['gyro_x'].mean(), 'std': baseline['gyro_x'].std()},
            'gyro_y': {'mean': baseline['gyro_y'].mean(), 'std': baseline['gyro_y'].std()},
            'gyro_z': {'mean': baseline['gyro_z'].mean(), 'std': baseline['gyro_z'].std()},
        }
    
    # Gesture statistics (1-10)
    for gesture_num in range(1, 11):
        gesture_data = df[df['gesture_number'] == gesture_num]
        if not gesture_data.empty:
            stats[f'gesture_{gesture_num}'] = {
                'accel_x': {'mean': gesture_data['accel_x'].mean(), 'std': gesture_data['accel_x'].std()},
                'accel_y': {'mean': gesture_data['accel_y'].mean(), 'std': gesture_data['accel_y'].std()},
                'accel_z': {'mean': gesture_data['accel_z'].mean(), 'std': gesture_data['accel_z'].std()},
                'gyro_x': {'mean': gesture_data['gyro_x'].mean(), 'std': gesture_data['gyro_x'].std()},
                'gyro_y': {'mean': gesture_data['gyro_y'].mean(), 'std': gesture_data['gyro_y'].std()},
                'gyro_z': {'mean': gesture_data['gyro_z'].mean(), 'std': gesture_data['gyro_z'].std()},
            }
    
    return stats

def create_visualization(df, stats):
    """Create interactive plotly visualization."""
    # Create subplots: 2 rows, 3 columns
    fig = make_subplots(
        rows=2, cols=3,
        subplot_titles=('Accelerometer X', 'Accelerometer Y', 'Accelerometer Z',
                       'Gyroscope X', 'Gyroscope Y', 'Gyroscope Z'),
        vertical_spacing=0.12,
        horizontal_spacing=0.10
    )
    
    # Convert timestamp to seconds relative to start
    df['time_sec'] = (df['timestamp_ms'] - df['timestamp_ms'].min()) / 1000.0
    
    # Color map for gesture types
    gesture_numbers = sorted(df['gesture_number'].unique())
    colors = ['blue' if gn == 0 else f'rgb({int(255 * (gn/10))}, 0, {int(255 * (1-gn/10))})' 
              for gn in gesture_numbers]
    
    # Plot each sensor axis
    sensor_configs = [
        ('accel_x', 1, 1, 'Accel X (m/s²)'),
        ('accel_y', 1, 2, 'Accel Y (m/s²)'),
        ('accel_z', 1, 3, 'Accel Z (m/s²)'),
        ('gyro_x', 2, 1, 'Gyro X (rad/s)'),
        ('gyro_y', 2, 2, 'Gyro Y (rad/s)'),
        ('gyro_z', 2, 3, 'Gyro Z (rad/s)'),
    ]
    
    for sensor_col, row, col, ylabel in sensor_configs:
        # Plot data by gesture number
        for i, gesture_num in enumerate(gesture_numbers):
            gesture_data = df[df['gesture_number'] == gesture_num]
            if not gesture_data.empty:
                name = 'Baseline' if gesture_num == 0 else f'Gesture {gesture_num}'
                fig.add_trace(
                    go.Scatter(
                        x=gesture_data['time_sec'],
                        y=gesture_data[sensor_col],
                        mode='lines',
                        name=name,
                        line=dict(color=colors[i], width=1.5),
                        showlegend=(row == 1 and col == 1),  # Show legend only on first subplot
                        hovertemplate=f'<b>{name}</b><br>' +
                                     f'Time: %{{x:.2f}}s<br>' +
                                     f'{ylabel}: %{{y:.4f}}<extra></extra>'
                    ),
                    row=row, col=col
                )
        
        # Add gesture prompt markers
        # Gesture prompts at: 3s, 5s, 7s, 9s, 11s, 13s, 15s, 17s, 19s, 21s
        for gesture_num in range(1, 11):
            prompt_time = 3.0 + (gesture_num - 1) * 2.0
            fig.add_vline(
                x=prompt_time,
                line_dash="dash",
                line_color="red",
                line_width=1,
                annotation_text=f"G{gesture_num}",
                annotation_position="top",
                row=row, col=col
            )
        
        fig.update_yaxes(title_text=ylabel, row=row, col=col)
    
    # Update x-axis labels
    fig.update_xaxes(title_text="Time (seconds)", row=2, col=1)
    fig.update_xaxes(title_text="Time (seconds)", row=2, col=2)
    fig.update_xaxes(title_text="Time (seconds)", row=2, col=3)
    
    # Update layout
    fig.update_layout(
        title_text="Sensor Data Visualization - Wrist Flexion Gestures",
        height=1000,
        showlegend=True,
        legend=dict(
            orientation="h",
            yanchor="bottom",
            y=1.02,
            xanchor="right",
            x=1
        )
    )
    
    # Add statistics table as annotation
    stats_text = "<b>Statistics:</b><br>"
    if 'baseline' in stats:
        stats_text += f"Baseline - Accel Y: μ={stats['baseline']['accel_y']['mean']:.3f}, σ={stats['baseline']['accel_y']['std']:.3f}<br>"
        stats_text += f"Baseline - Gyro Z: μ={stats['baseline']['gyro_z']['mean']:.4f}, σ={stats['baseline']['gyro_z']['std']:.4f}<br>"
    
    # Average gesture statistics
    gesture_accel_y_means = []
    gesture_gyro_z_means = []
    for i in range(1, 11):
        key = f'gesture_{i}'
        if key in stats:
            gesture_accel_y_means.append(stats[key]['accel_y']['mean'])
            gesture_gyro_z_means.append(stats[key]['gyro_z']['mean'])
    
    if gesture_accel_y_means:
        avg_accel_y = sum(gesture_accel_y_means) / len(gesture_accel_y_means)
        avg_gyro_z = sum(gesture_gyro_z_means) / len(gesture_gyro_z_means)
        stats_text += f"Avg Gesture - Accel Y: μ={avg_accel_y:.3f}<br>"
        stats_text += f"Avg Gesture - Gyro Z: μ={avg_gyro_z:.4f}"
    
    fig.add_annotation(
        text=stats_text,
        xref="paper", yref="paper",
        x=0.02, y=0.98,
        xanchor="left", yanchor="top",
        bgcolor="rgba(255,255,255,0.8)",
        bordercolor="black",
        borderwidth=1,
        font=dict(size=10)
    )
    
    return fig

def main():
    if len(sys.argv) < 2:
        print("Usage: python visualize_sensor_data.py <csv_file_path>")
        sys.exit(1)
    
    csv_file = sys.argv[1]
    
    print(f"Loading CSV file: {csv_file}")
    df = load_and_validate_csv(csv_file)
    
    print(f"Loaded {len(df)} data points")
    print(f"Time range: {(df['timestamp_ms'].max() - df['timestamp_ms'].min()) / 1000.0:.2f} seconds")
    
    print("Calculating statistics...")
    stats = calculate_statistics(df)
    
    print("Creating visualization...")
    fig = create_visualization(df, stats)
    
    # Save to temporary HTML file and open in browser
    with tempfile.NamedTemporaryFile(mode='w', suffix='.html', delete=False, dir=os.getcwd()) as f:
        html_file = f.name
        fig.write_html(html_file)
        
        print(f"\nVisualization saved to: {html_file}")
        print("Opening in browser...")
        
        # Open in default browser (Chrome)
        webbrowser.open(f'file://{os.path.abspath(html_file)}')
        
        print("\nVisualization opened in browser!")
        print("You can close this script. The HTML file will remain for future viewing.")

if __name__ == "__main__":
    main()

