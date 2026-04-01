#!/bin/bash

# ADB Wireless Connection Script
# Automatically detects and connects to Android device via hotspot

set -e

echo "🔌 ADB Wireless Connection Setup"
echo "================================"

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ Error: adb command not found. Please install Android SDK Platform Tools."
    exit 1
fi

# Get Android hotspot gateway IP (Android becomes the default gateway when tethering)
echo ""
echo "📶 Detecting Android hotspot gateway..."
gateway_ip=$(route -n get default 2>/dev/null | grep gateway | awk '{print $2}' | head -n 1 || true)

if [ -z "$gateway_ip" ]; then
    # Fallback: try netstat
    gateway_ip=$(netstat -rn | grep default | grep -v '::' | awk '{print $2}' | head -n 1 || true)
fi

if [ -z "$gateway_ip" ]; then
    echo "❌ Could not detect default gateway. Please ensure you're connected to the Android hotspot."
    exit 1
fi

echo "✅ Android Gateway IP: $gateway_ip"

# Extract subnet (e.g., 192.168.43.x -> 192.168.43)
subnet=$(echo "$gateway_ip" | cut -d. -f1-3)
echo "🌐 Subnet: $subnet.0/24"

# Get connected devices via USB
echo ""
echo "📱 Checking connected devices..."
devices=$(adb devices | grep -v "List" | grep "device$" | grep -v "wireless" || true)

if [ -z "$devices" ]; then
    echo "❌ No USB connected devices found. Please connect a device via USB and enable USB debugging."
    exit 1
fi

# Use gateway IP as Android device IP (since Android is the gateway when tethering)
device_ip="$gateway_ip"

# Select the first USB connected device
device_id=$(echo "$devices" | head -n 1 | awk '{print $1}')
echo "✅ Selected device: $device_id"
echo "✅ Device IP: $device_ip"

# Enable TCP/IP mode on port 5555
echo ""
echo "🔄 Enabling TCP/IP mode on port 5555..."
adb -s "$device_id" tcpip 5555

# Wait a moment for the device to switch modes
sleep 2

# Connect wirelessly
echo ""
echo "📡 Connecting to $device_ip:5555..."
adb connect "$device_ip:5555"

# Wait for connection to establish
sleep 2

# Verify wireless connection
echo ""
echo "🔍 Verifying connection..."
connected_devices=$(adb devices | grep "$device_ip:5555" | grep "device$" || true)

if [ -n "$connected_devices" ]; then
    echo "✅ Successfully connected wirelessly to $device_ip:5555"
    echo ""
    echo "📋 Current ADB devices:"
    adb devices
    echo ""
    echo "💡 You can now disconnect the USB cable."
    echo "   The device will remain connected via Wi-Fi."
    echo ""
    echo "🔧 To disconnect later, use:"
    echo "   adb disconnect $device_ip:5555"
    echo "   adb usb"
else
    echo "⚠️  Wireless connection may have failed."
    echo "    Please check:"
    echo "    1. Device and computer are on the same Wi-Fi network"
    echo "    2. USB debugging is enabled"
    echo "    3. Try running this script again with USB connected"
    exit 1
fi
