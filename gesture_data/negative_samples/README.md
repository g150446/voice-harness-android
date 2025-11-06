# Negative Samples Data

This folder contains sensor data for negative samples (non-gesture movements).

These are movements that should NOT trigger gesture detection, used to improve detection accuracy and reduce false positives.

## Data Format

CSV files contain the following columns:
- timestamp_ms: Timestamp in milliseconds
- accel_x, accel_y, accel_z: Accelerometer values (m/s²)
- gyro_x, gyro_y, gyro_z: Gyroscope values (rad/s)
- gesture_type: Always "negative" for negative samples
- gesture_number: Sample number (1-10)

## Collection Process

1. Baseline calibration (3 seconds)
2. 10 negative sample prompts at 2-second intervals
3. During prompts, perform natural movements that should NOT be detected as wrist flexion

