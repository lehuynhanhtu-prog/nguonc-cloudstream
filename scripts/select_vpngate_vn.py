#!/usr/bin/env python3
import base64
import csv
import sys
import urllib.request

URL = "https://www.vpngate.net/api/iphone/"

req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req, timeout=30) as r:
    raw = r.read().decode("utf-8-sig", "replace")

header = None
rows = []
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
    if item.get("CountryShort") == "VN" and item.get("OpenVPN_ConfigData_Base64"):
        try:
            item["_score"] = int(item.get("Score") or 0)
        except Exception:
            item["_score"] = 0
        rows.append(item)

if not rows:
    raise SystemExit("No Viet Nam VPN Gate OpenVPN relay available")

rows.sort(key=lambda item: item["_score"], reverse=True)
selected = rows[0]
config = base64.b64decode(selected["OpenVPN_ConfigData_Base64"]).decode("utf-8", "replace")
config = config.replace("auth-user-pass\n", "auth-user-pass /tmp/vpn-auth.txt\n")

open("/tmp/vpn.ovpn", "w").write(config)
open("/tmp/vpn-auth.txt", "w").write("vpn\nvpn\n")
print(
    "VPN_SELECTED",
    selected.get("HostName"),
    selected.get("IP"),
    selected.get("Score"),
    selected.get("Speed"),
)
