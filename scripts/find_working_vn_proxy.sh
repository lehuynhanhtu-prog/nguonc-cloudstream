#!/usr/bin/env bash
set -euo pipefail

# Public VN proxies observed live on 2026-10-29/30. Each candidate is
# independently verified from this runner before it is used.
candidates=(
  "socks5h://117.5.251.88:5005"
  "socks5h://103.182.22.198:9000"
  "socks5h://103.182.22.58:9000"
  "socks5h://103.182.22.179:9000"
  "socks5h://103.182.22.187:9000"
  "socks5h://103.182.22.175:9000"
  "socks5h://14.225.205.152:1081"
  "socks5h://103.182.22.51:9000"
  "http://123.58.199.232:8168"
  "http://103.28.37.131:3128"
  "http://42.96.15.210:60000"
  "http://103.82.39.87:5004"
)

target="https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=1"

for proxy in "${candidates[@]}"; do
  echo "TRY_PROXY $proxy"

  if [[ "$proxy" == socks5h://* ]]; then
    curl_proxy=(--proxy "$proxy")
  else
    curl_proxy=(--proxy "$proxy")
  fi

  trace="$(curl -kfsS -L --max-time 8 "${curl_proxy[@]}" https://www.cloudflare.com/cdn-cgi/trace 2>/dev/null || true)"
  loc="$(printf '%s\n' "$trace" | sed -n 's/^loc=//p' | head -n1)"
  ip="$(printf '%s\n' "$trace" | sed -n 's/^ip=//p' | head -n1)"
  echo "PROXY_TRACE loc=${loc:-?} ip=${ip:-?}"

  if [[ "$loc" != "VN" ]]; then
    continue
  fi

  status="$(curl -k -sS -L --max-time 12 "${curl_proxy[@]}"     -A 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'     -H 'Accept: application/json,text/plain,*/*'     -e 'https://phim.nguonc.com/'     -o /tmp/nguonc-probe.json -w '%{http_code}' "$target" 2>/dev/null || true)"
  echo "NGUONC_PROXY_HTTP $status"

  if [[ "$status" == "200" ]] && grep -q '"items"' /tmp/nguonc-probe.json; then
    echo "WORKING_VN_PROXY $proxy"
    printf '%s' "$proxy" > /tmp/working_vn_proxy
    exit 0
  fi
done

echo "NO_WORKING_VN_PROXY"
exit 1
