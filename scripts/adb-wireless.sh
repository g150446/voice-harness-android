#!/bin/bash

# ADB Wireless Connection Script
# USB で接続した端末の Wi-Fi/テザリング IP を取得し、tcpip 5555 で接続する。

set -euo pipefail

echo "ADB Wireless Connection Setup"
echo "============================="

if ! command -v adb &> /dev/null; then
    echo "Error: adb command not found."
    exit 1
fi

echo ""
echo "Checking USB-connected devices..."
device_id=$(adb devices | awk '/\tdevice$/ && $1 !~ /:/{print $1; exit}')
if [[ -z "$device_id" ]]; then
    echo "Error: No USB device found. Connect via USB with USB debugging enabled."
    exit 1
fi
echo "Selected device: $device_id"

echo ""
echo "Reading device network addresses..."
device_ip=""
# Prefer hotspot (ap0) then Wi-Fi (wlan0), then any non-loopback IPv4.
for iface in ap0 wlan0; do
    candidate=$(adb -s "$device_id" shell "ip -f inet -o addr show $iface 2>/dev/null" \
        | awk '{print $4}' | cut -d/ -f1 | head -n1 | tr -d '\r')
    if [[ -n "${candidate:-}" ]]; then
        device_ip="$candidate"
        echo "Found $iface: $device_ip"
        break
    fi
done

if [[ -z "$device_ip" ]]; then
    device_ip=$(adb -s "$device_id" shell "ip -f inet -o addr show" 2>/dev/null \
        | awk '$2 != "lo" {print $4}' | cut -d/ -f1 | head -n1 | tr -d '\r')
fi

if [[ -z "$device_ip" ]]; then
    echo "Error: Could not find a device IPv4 address."
    echo "Connect this Mac to the phone hotspot, or join the phone to the same Wi-Fi."
    exit 1
fi

echo "Device IP: $device_ip"

echo ""
echo "Checking reachability..."
if ! ping -c 1 -W 1000 "$device_ip" >/dev/null 2>&1; then
    echo "Error: $device_ip is not reachable from this Mac."
    echo "Connect Mac to the phone hotspot (or same Wi-Fi), then rerun."
    exit 1
fi
echo "Reachable: $device_ip"

echo ""
echo "Enabling TCP/IP mode on port 5555..."
adb -s "$device_id" tcpip 5555
sleep 2

echo ""
echo "Connecting to $device_ip:5555..."
adb connect "$device_ip:5555"
sleep 1

if adb devices | grep -q "$device_ip:5555[[:space:]]*device"; then
    echo ""
    echo "Success: wireless ADB at $device_ip:5555"
    adb devices -l
    echo ""
    echo "You can unplug USB. Later:"
    echo "  adb disconnect $device_ip:5555"
else
    echo "Wireless connection failed."
    exit 1
fi
