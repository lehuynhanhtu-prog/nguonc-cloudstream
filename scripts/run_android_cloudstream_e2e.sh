#!/usr/bin/env bash
set -euo pipefail

proxy="$(cat /tmp/working_vn_proxy)"
remote="${proxy/socks5h:\/\//socks5:\/\/}"
echo "ANDROID_REMOTE_PROXY $remote"

pproxy -l http://0.0.0.0:8899 -r "$remote" > /tmp/pproxy.log 2>&1 &
bridge_pid=$!
cleanup() {
  kill "$bridge_pid" 2>/dev/null || true
}
trap cleanup EXIT

ok=0
for i in {1..20}; do
  trace="$(curl -kfsS -x http://127.0.0.1:8899 --max-time 8 https://www.cloudflare.com/cdn-cgi/trace || true)"
  echo "$trace" | grep -E '^(ip|loc|colo|warp)=' || true
  if echo "$trace" | grep -q '^loc=VN$'; then
    status="$(curl -k -sS -L -x http://127.0.0.1:8899 --max-time 12 \
      -A 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36' \
      -o /tmp/android-proxy-probe.json -w '%{http_code}' \
      'https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=1' || true)"
    if [ "$status" = "200" ] && grep -q '"items"' /tmp/android-proxy-probe.json; then
      ok=1
      break
    fi
  fi
  sleep 1
done

if [ "$ok" != 1 ]; then
  echo "ANDROID_PROXY_BRIDGE_FAIL"
  cat /tmp/pproxy.log || true
  exit 1
fi

python scripts/android_cloudstream_e2e.py
