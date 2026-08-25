#!/usr/bin/env bash
# Connect adb to this machine's Android device over Tailscale (or enable TCP via USB).
#
# Usage:
#   ./scripts/adb-tailscale.sh              # connect using default Tailscale hostname
#   ./scripts/adb-tailscale.sh connect      # same
#   ./scripts/adb-tailscale.sh enable-usb    # USB attached: adb tcpip 5555 then connect
#   ./scripts/adb-tailscale.sh status
#   ./scripts/adb-tailscale.sh disconnect
#
# Defaults (override with env):
#   TS_HOST=motorola-razr-50s
#   TS_PORT=5555

set -euo pipefail

TS_HOST="${TS_HOST:-motorola-razr-50s}"
TS_PORT="${TS_PORT:-5555}"

resolve_ip() {
  if command -v tailscale >/dev/null 2>&1; then
    # Prefer live status line for the host
    local ip
    ip="$(tailscale status --json 2>/dev/null | python3 -c "
import json,sys
host=sys.argv[1]
d=json.load(sys.stdin)
# Self
if d.get('Self',{}).get('HostName','').lower().replace('_','-')==host.lower().replace('_','-'):
    addrs=d['Self'].get('TailscaleIPs') or []
    if addrs: print(addrs[0]); raise SystemExit
# Peers
for p in (d.get('Peer') or {}).values():
    hn=(p.get('HostName') or '').lower().replace('_','-')
    dns=(p.get('DNSName') or '').split('.')[0].lower().replace('_','-')
    if host.lower().replace('_','-') in (hn,dns) or host.lower() in hn:
        addrs=p.get('TailscaleIPs') or []
        if addrs:
            print(addrs[0]); raise SystemExit
" "$TS_HOST" 2>/dev/null || true)"
    if [[ -n "${ip:-}" ]]; then
      printf '%s\n' "$ip"
      return
    fi
    # Fallback: plain status text
    ip="$(tailscale status 2>/dev/null | awk -v h="$TS_HOST" '
      $0 ~ h { print $1; exit }
    ' || true)"
    if [[ -n "${ip:-}" ]]; then
      printf '%s\n' "$ip"
      return
    fi
  fi
  # Last resort: MagicDNS / hosts
  if command -v dig >/dev/null 2>&1; then
    dig +short "$TS_HOST" 2>/dev/null | head -1
    return
  fi
  getent hosts "$TS_HOST" 2>/dev/null | awk '{print $1; exit}'
}

cmd_status() {
  echo "TS_HOST=$TS_HOST TS_PORT=$TS_PORT"
  if command -v tailscale >/dev/null 2>&1; then
    echo "--- tailscale ---"
    tailscale status 2>/dev/null | grep -iE "razr|android|macbook|${TS_HOST}" || tailscale status 2>/dev/null | head -15
  fi
  echo "--- adb devices ---"
  adb devices -l
}

cmd_enable_usb() {
  local serial
  serial="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
  if [[ -z "${serial:-}" ]]; then
    echo "ERROR: no USB adb device. Plug in USB and enable USB debugging." >&2
    exit 1
  fi
  echo "USB device: $serial → tcpip $TS_PORT"
  adb -s "$serial" tcpip "$TS_PORT"
  sleep 1
  cmd_connect
}

cmd_connect() {
  local ip
  ip="$(resolve_ip)"
  if [[ -z "${ip:-}" ]]; then
    echo "ERROR: could not resolve Tailscale IP for host '$TS_HOST'." >&2
    echo "Check: tailscale status  (device online? same tailnet?)" >&2
    exit 1
  fi
  local target="${ip}:${TS_PORT}"
  echo "Connecting adb → $target  (host=$TS_HOST)"
  adb connect "$target"
  adb devices -l
  if adb -s "$target" shell true 2>/dev/null; then
    echo "OK: wireless adb ready ($target)"
    echo "Install: ./gradlew :app:installDebug"
    echo "Logcat:  adb -s $target logcat -s VoiceProcessor BleManager"
  else
    echo "ERROR: connected but shell failed. Re-run with USB:" >&2
    echo "  ./scripts/adb-tailscale.sh enable-usb" >&2
    exit 1
  fi
}

cmd_disconnect() {
  local ip
  ip="$(resolve_ip || true)"
  if [[ -n "${ip:-}" ]]; then
    adb disconnect "${ip}:${TS_PORT}" || true
  fi
  adb disconnect || true
  adb devices -l
}

usage() {
  sed -n '2,14p' "$0"
}

case "${1:-connect}" in
  connect|c) cmd_connect ;;
  enable-usb|usb|enable) cmd_enable_usb ;;
  status|s) cmd_status ;;
  disconnect|d) cmd_disconnect ;;
  -h|--help|help) usage ;;
  *) echo "Unknown command: $1" >&2; usage; exit 1 ;;
esac
