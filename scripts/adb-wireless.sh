#!/usr/bin/env bash
# Wireless adb for the Android phone app.
#
# `adb tcpip 5555` survives until the phone reboots, so once `setup` has run over USB
# you can reconnect from Wi-Fi alone. Candidate addresses, tried in order:
#   1. the last address that worked (remembered in $STATE_FILE)
#   2. the Mac's default gateway — correct when the Mac is on the phone's hotspot
#   3. the phone's Tailscale address — works from any network
#
# Usage:
#   ./scripts/adb-wireless.sh            # connect wirelessly; falls back to USB setup
#   ./scripts/adb-wireless.sh connect    # wireless only, never touches USB
#   ./scripts/adb-wireless.sh setup      # USB attached: enable tcpip, connect, remember
#   ./scripts/adb-wireless.sh status
#   ./scripts/adb-wireless.sh disconnect
#
# Env: ADB_TCP_PORT (default 5555), TS_HOST (default motorola-razr-50s)

set -euo pipefail

PORT="${ADB_TCP_PORT:-5555}"
TS_HOST="${TS_HOST:-motorola-razr-50s}"
STATE_FILE="${XDG_CACHE_HOME:-$HOME/.cache}/voice-harness/adb-wireless.host"

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found." >&2; exit 1; }

remember() {
  mkdir -p "$(dirname "$STATE_FILE")"
  printf '%s\n' "$1" > "$STATE_FILE"
}

saved_ip()   { [[ -f "$STATE_FILE" ]] && tr -d '[:space:]' < "$STATE_FILE" || true; }
gateway_ip() { route -n get default 2>/dev/null | awk '/gateway:/{print $2; exit}'; }

tailscale_ip() {
  command -v tailscale >/dev/null 2>&1 || return 0
  tailscale status 2>/dev/null | awk -v h="$TS_HOST" '$2==h {print $1; exit}'
}

usb_serial() {
  # A USB transport has no ":" in its serial; wireless transports look like host:port.
  adb devices | awk '/\tdevice$/ && $1 !~ /:/ {print $1; exit}'
}

# Succeeds only if the transport actually answers a shell command.
try_connect() {
  local ip="$1" target="$ip:$PORT"
  [[ -n "$ip" ]] || return 1
  adb connect "$target" >/dev/null 2>&1 || true
  if adb -s "$target" shell true >/dev/null 2>&1; then
    remember "$ip"
    echo "OK: wireless adb at $target"
    return 0
  fi
  adb disconnect "$target" >/dev/null 2>&1 || true
  return 1
}

cmd_connect() {
  local tried=()
  for ip in "$(saved_ip)" "$(gateway_ip)" "$(tailscale_ip)"; do
    [[ -n "$ip" ]] || continue
    [[ " ${tried[*]-} " == *" $ip "* ]] && continue
    tried+=("$ip")
    echo "Trying $ip:$PORT ..."
    try_connect "$ip" && return 0
  done
  echo "Wireless connect failed (tried: ${tried[*]-none})." >&2
  echo "The phone may have rebooted, which clears tcpip mode." >&2
  echo "Plug in USB and run: ./scripts/adb-wireless.sh setup" >&2
  return 1
}

cmd_setup() {
  local serial
  serial="$(usb_serial)"
  if [[ -z "${serial:-}" ]]; then
    echo "ERROR: no USB device. Plug in USB with USB debugging enabled." >&2
    exit 1
  fi
  echo "USB device: $serial"

  # Collect every address the phone has, then let try_connect decide which one this
  # Mac can actually reach — the phone can hold a hotspot the Mac has since left.
  local candidates=()
  for iface in ap0 wlan0; do
    local ip
    ip="$(adb -s "$serial" shell "ip -f inet -o addr show $iface 2>/dev/null" \
      | awk '{print $4}' | cut -d/ -f1 | head -n1 | tr -d '\r')"
    [[ -n "$ip" ]] && { echo "Found $iface: $ip"; candidates+=("$ip"); }
  done
  local ts
  ts="$(tailscale_ip)"
  [[ -n "$ts" ]] && { echo "Found tailscale: $ts"; candidates+=("$ts"); }
  if [[ ${#candidates[@]} -eq 0 ]]; then
    echo "ERROR: no address. Join the Mac to the phone hotspot or same Wi-Fi." >&2
    exit 1
  fi

  echo "Enabling tcpip $PORT ..."
  adb -s "$serial" tcpip "$PORT"
  sleep 2
  for ip in "${candidates[@]}"; do
    echo "Trying $ip:$PORT ..."
    if try_connect "$ip"; then
      echo "USB can be unplugged now."
      adb devices -l
      return 0
    fi
  done
  echo "ERROR: none of ${candidates[*]} answered on port $PORT from this Mac." >&2
  exit 1
}

cmd_status() {
  echo "port=$PORT  saved=$(saved_ip || echo none)"
  echo "gateway=$(gateway_ip || echo none)  tailscale=$(tailscale_ip || echo none)"
  echo "--- adb devices ---"
  adb devices -l
}

cmd_disconnect() {
  local ip
  ip="$(saved_ip || true)"
  [[ -n "$ip" ]] && adb disconnect "$ip:$PORT" || true
  adb devices -l
}

case "${1:-auto}" in
  auto)                cmd_connect || cmd_setup ;;
  connect|c)           cmd_connect ;;
  setup|usb)           cmd_setup ;;
  status|s)            cmd_status ;;
  disconnect|d)        cmd_disconnect ;;
  -h|--help|help)      sed -n '2,20p' "$0" ;;
  *) echo "Unknown command: $1" >&2; sed -n '2,20p' "$0"; exit 1 ;;
esac
