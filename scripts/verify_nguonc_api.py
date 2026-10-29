import json
import sys
import urllib.parse
import urllib.request

BASE = "https://phim.nguonc.com/api"
HEADERS = {
    "User-Agent": "Mozilla/5.0 NguonC-CloudStream-CI/1.0",
    "Accept": "application/json",
}

def fetch(path: str):
    req = urllib.request.Request(f"{BASE}/{path}", headers=HEADERS)
    with urllib.request.urlopen(req, timeout=20) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status} for {path}")
        return json.loads(response.read().decode("utf-8"))

latest = fetch("films/phim-moi-cap-nhat?page=1")
if latest.get("status") != "success":
    raise RuntimeError(f"Latest endpoint returned status={latest.get('status')!r}")

items = latest.get("items") or []
if not items:
    raise RuntimeError("Latest endpoint returned no items")

slug = (items[0].get("slug") or "").strip()
if not slug:
    raise RuntimeError("First latest item has no slug")

detail = fetch(f"film/{urllib.parse.quote(slug)}")
if detail.get("status") != "success":
    raise RuntimeError(f"Detail endpoint returned status={detail.get('status')!r}")

movie = detail.get("movie") or {}
if not movie.get("name"):
    raise RuntimeError("Detail endpoint returned no movie name")

servers = detail.get("episodes") or movie.get("episodes") or []
if not servers:
    raise RuntimeError("Detail endpoint returned no episode servers")

has_stream = False
for server in servers:
    episode_items = server.get("items") or server.get("server_data") or []
    for episode in episode_items:
        if any((episode.get(key) or "").strip() for key in ("embed", "link_embed", "m3u8", "link_m3u8")):
            has_stream = True
            break
    if has_stream:
        break

if not has_stream:
    raise RuntimeError("No playable/embed URL was found in detail response")

print(f"NguonC API smoke test passed: {movie.get('name')} ({slug})")
