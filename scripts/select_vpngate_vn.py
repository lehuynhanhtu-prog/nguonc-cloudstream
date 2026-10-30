#!/usr/bin/env python3
import base64
import csv
import os
import urllib.request

URL = "https://www.vpngate.net/api/iphone/"
MAX_CONFIGS = 10

req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req, timeout=30) as r:
    raw = r.read().decode("utf-8-sig", "replace")

header = None
candidates = []
for line in raw.splitlines():
    if not line or line.startswith("*"):
        continue
    if line.startswith("#HostName"):
        header = line[1:].split(",")
        continue
    if not header:
        continue
    try:
        values = next(csv.reader([line]))
    except Exception:
        continue
    if len(values) != len(header):
        continue
    item = dict(zip(header, values))
    if item.get("CountryShort") != "VN" or not item.get("OpenVPN_ConfigData_Base64"):
        continue
    try:
        config = base64.b64decode(item["OpenVPN_ConfigData_Base64"]).decode("utf-8", "replace")
    except Exception:
        continue
    # TCP is much more reliable from hosted CI than UDP.
    if "proto tcp" not in config.lower():
        continue
    try:
        score = int(item.get("Score") or 0)
    except Exception:
        score = 0
    item["_score"] = score
    item["_config"] = config
    candidates.append(item)

if not candidates:
    raise SystemExit("No Viet Nam VPN Gate TCP OpenVPN relay available")

candidates.sort(key=lambda item: item["_score"], reverse=True)
seen = set()
selected = []
for item in candidates:
    key = (item.get("IP"), item.get("HostName"))
    if key in seen:
        continue
    seen.add(key)
    selected.append(item)
    if len(selected) >= MAX_CONFIGS:
        break

open("/tmp/vpn-auth.txt", "w").write("vpn\nvpn\n")
for index, item in enumerate(selected):
    config = item["_config"].replace(
        "auth-user-pass\n",
        "auth-user-pass /tmp/vpn-auth.txt\n",
    )
    path = f"/tmp/vpn-{index}.ovpn"
    open(path, "w").write(config)
    print(
        "VPN_CANDIDATE",
        index,
        item.get("HostName"),
        item.get("IP"),
        item.get("Score"),
        item.get("Speed"),
        path,
    )

print("VPN_CANDIDATE_COUNT", len(selected))
