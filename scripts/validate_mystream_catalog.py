#!/usr/bin/env python3
import json
import sys
from pathlib import Path

CATALOG = Path("catalog/mystream.json")
ALLOWED_ITEM_TYPES = {"movie", "series", "tv", "tvseries", "anime"}
ALLOWED_SOURCE_TYPES = {"video", "mp4", "hls", "m3u8", "dash", "mpd", "embed", "extractor", "auto", ""}

def fail(message: str):
    print(f"MYSTREAM_CATALOG_INVALID: {message}")
    raise SystemExit(1)

def check_source(source, where):
    if not isinstance(source, dict):
        fail(f"{where}: source must be an object")
    url = str(source.get("url", "")).strip()
    if not url:
        fail(f"{where}: source.url is required")
    source_type = str(source.get("type", "")).strip().lower()
    if source_type not in ALLOWED_SOURCE_TYPES:
        fail(f"{where}: unsupported source.type={source_type!r}")

def check_subtitle(subtitle, where):
    if not isinstance(subtitle, dict):
        fail(f"{where}: subtitle must be an object")
    if not str(subtitle.get("url", "")).strip():
        fail(f"{where}: subtitle.url is required")

def main():
    if not CATALOG.exists():
        fail("catalog/mystream.json is missing")

    try:
        data = json.loads(CATALOG.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"invalid JSON: {exc}")

    if not isinstance(data, dict):
        fail("root must be an object")
    if int(data.get("version", 0)) < 2:
        fail("catalog version must be >= 2")

    items = data.get("items")
    if not isinstance(items, list) or not items:
        fail("items must be a non-empty array")

    ids = set()
    playable = 0

    for idx, item in enumerate(items):
        where = f"items[{idx}]"
        if not isinstance(item, dict):
            fail(f"{where}: item must be an object")

        item_id = str(item.get("id", "")).strip()
        title = str(item.get("title", "")).strip()
        item_type = str(item.get("type", "movie")).strip().lower()

        if not item_id:
            fail(f"{where}: id is required")
        if item_id in ids:
            fail(f"{where}: duplicate id={item_id!r}")
        ids.add(item_id)

        if not title:
            fail(f"{where}: title is required")
        if item_type not in ALLOWED_ITEM_TYPES:
            fail(f"{where}: unsupported type={item_type!r}")

        for sidx, source in enumerate(item.get("sources", []) or []):
            check_source(source, f"{where}.sources[{sidx}]")
        for sidx, subtitle in enumerate(item.get("subtitles", []) or []):
            check_subtitle(subtitle, f"{where}.subtitles[{sidx}]")

        episodes = item.get("episodes", []) or []
        if episodes and not isinstance(episodes, list):
            fail(f"{where}: episodes must be an array")

        item_has_source = bool(item.get("sources"))
        episode_has_source = False

        for eidx, episode in enumerate(episodes):
            ewhere = f"{where}.episodes[{eidx}]"
            if not isinstance(episode, dict):
                fail(f"{ewhere}: episode must be an object")
            sources = episode.get("sources", []) or []
            for sidx, source in enumerate(sources):
                check_source(source, f"{ewhere}.sources[{sidx}]")
            for sidx, subtitle in enumerate(episode.get("subtitles", []) or []):
                check_subtitle(subtitle, f"{ewhere}.subtitles[{sidx}]")
            if sources:
                episode_has_source = True

        if not item_has_source and not episode_has_source:
            fail(f"{where}: item has no playable source")
        playable += 1

    print(f"MYSTREAM_CATALOG_OK items={len(items)} playable={playable} version={data.get('version')}")

if __name__ == "__main__":
    main()
