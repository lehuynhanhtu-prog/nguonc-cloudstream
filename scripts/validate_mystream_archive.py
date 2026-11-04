#!/usr/bin/env python3
import json
import time
import urllib.parse
import urllib.request

QUERY = "mediatype:movies AND collection:feature_films"
SEARCH = "https://archive.org/advancedsearch.php"
META = "https://archive.org/metadata/"


def fetch_json(url, retries=3):
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "MyStream-CI/3"})
            with urllib.request.urlopen(req, timeout=25) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as exc:
            last = exc
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"request failed: {url}: {last}")


def main():
    params = [
        ("q", QUERY),
        ("fl[]", "identifier"),
        ("fl[]", "title"),
        ("fl[]", "year"),
        ("sort[]", "downloads desc"),
        ("rows", "10"),
        ("page", "1"),
        ("output", "json"),
    ]
    root = fetch_json(SEARCH + "?" + urllib.parse.urlencode(params))
    response = root.get("response") or {}
    docs = response.get("docs") or []
    total = int(response.get("numFound") or 0)

    if total <= 30:
        raise RuntimeError(f"expected paginated catalog, numFound={total}")
    if not docs:
        raise RuntimeError("archive search returned no docs")

    playable = None
    for doc in docs[:6]:
        identifier = str(doc.get("identifier") or "").strip()
        if not identifier:
            continue
        meta = fetch_json(META + urllib.parse.quote(identifier, safe=""))
        files = meta.get("files") or []
        mp4 = [
            f for f in files
            if str(f.get("name") or "").lower().endswith(".mp4")
        ]
        if mp4:
            playable = (identifier, len(mp4))
            break

    if playable is None:
        raise RuntimeError("no MP4 source found in sampled archive items")

    print(
        f"MYSTREAM_ARCHIVE_OK total={total} "
        f"sampled={len(docs)} playable_id={playable[0]} mp4_files={playable[1]}"
    )


if __name__ == "__main__":
    main()
