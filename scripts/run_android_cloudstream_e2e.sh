#!/usr/bin/env bash
set -euo pipefail

chmod +x scripts/find_working_vn_proxy.sh
failed_file=/tmp/failed_vn_proxies
: > "$failed_file"
bridge_pid=""

cleanup() {
  if [ -n "$bridge_pid" ]; then
    kill "$bridge_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

ok=0
for attempt in 1 2 3; do
  echo "ANDROID_PROXY_ATTEMPT $attempt"
  rm -f /tmp/working_vn_proxy
  FAILED_PROXY_FILE="$failed_file" scripts/find_working_vn_proxy.sh

  proxy="$(cat /tmp/working_vn_proxy)"
  remote="${proxy/socks5h:\/\//socks5:\/\/}"
  echo "ANDROID_REMOTE_PROXY $remote"

  if [ -n "$bridge_pid" ]; then
    kill "$bridge_pid" 2>/dev/null || true
    wait "$bridge_pid" 2>/dev/null || true
  fi

  pproxy -l http://0.0.0.0:8899 -r "$remote" > /tmp/pproxy.log 2>&1 &
  bridge_pid=$!
  sleep 1

  for i in {1..5}; do
    trace="$(curl -kfsS -x http://127.0.0.1:8899 --max-time 8 https://www.cloudflare.com/cdn-cgi/trace || true)"
    echo "$trace" | grep -E '^(ip|loc|colo|warp)=' || true
    if echo "$trace" | grep -q '^loc=VN$'; then
      status="$(curl -k -sS -L -x http://127.0.0.1:8899 --max-time 12 \
        -A 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36' \
        -H 'Accept: application/json,text/plain,*/*' \
        -e 'https://phim.nguonc.com/' \
        -o /tmp/android-proxy-probe.json -w '%{http_code}' \
        'https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=1' || true)"
      echo "ANDROID_BRIDGE_NGUONC_HTTP $status"
      if [ "$status" = "200" ] && grep -q '"items"' /tmp/android-proxy-probe.json; then
        ok=1
        break
      fi
    fi
    sleep 1
  done

  if [ "$ok" = 1 ]; then
    break
  fi

  echo "$proxy" >> "$failed_file"
  echo "ANDROID_PROXY_REJECTED $proxy"
done

if [ "$ok" != 1 ]; then
  echo "ANDROID_PROXY_BRIDGE_FAIL"
  cat /tmp/pproxy.log || true
  exit 1
fi

python scripts/android_cloudstream_e2e.py
