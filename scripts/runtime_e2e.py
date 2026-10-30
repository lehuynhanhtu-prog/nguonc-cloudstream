#!/usr/bin/env python3
import base64
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.parse

UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
KNOWN_SLUG = "gign-luc-luong-tinh-nhue"
API_BASES = ["https://phim.nguonc.com/api", "https://nguonc.com/api"]

def curl(url, *, referer=None, method="GET", headers=None, body=None, timeout=25):
    cmd = ["curl", "-sS", "-L", "--compressed", "--max-time", str(timeout), "-A", UA]
    # Prefer HTTP/2 when the local curl supports it.
    probe = subprocess.run(["curl", "--http2", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if probe.returncode == 0:
        cmd.append("--http2")
    if referer:
        cmd += ["-e", referer]
    for k, v in (headers or {}).items():
        cmd += ["-H", f"{k}: {v}"]
    if method != "GET":
        cmd += ["-X", method]
    if body is not None:
        cmd += ["--data-binary", body]
    cmd += ["-w", "\n__HTTP_STATUS__:%{http_code}\n", url]
    p = subprocess.run(cmd, capture_output=True, timeout=timeout + 10)
    text = p.stdout.decode("utf-8", "replace")
    m = re.search(r"\n__HTTP_STATUS__:(\d+)\n?$", text)
    status = int(m.group(1)) if m else 0
    if m:
        text = text[:m.start()]
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace").strip() or f"curl exit {p.returncode}")
    return status, text

def get_json(url, referer=None):
    status, text = curl(url, referer=referer, headers={"Accept": "application/json,text/plain,*/*"})
    if status != 200:
        raise RuntimeError(f"HTTP {status} {url}")
    return json.loads(text)

def origin(url):
    p = urllib.parse.urlsplit(url)
    return f"{p.scheme}://{p.netloc}"

def resolve(base, value):
    return urllib.parse.urljoin(base, value)

def find_slug():
    errors = []
    for base in API_BASES:
        try:
            data = get_json(f"{base}/films/phim-moi-cap-nhat?page=1", "https://phim.nguonc.com/")
            items = data.get("items") or []
            if items and items[0].get("slug"):
                print(f"API_OK {base} items={len(items)}")
                return items[0]["slug"], base
        except Exception as e:
            errors.append(f"{base}: {e}")
    print("API_LIST_UNAVAILABLE " + " | ".join(errors))
    return KNOWN_SLUG, None

def find_episode(slug, api_base):
    # First try API detail (same route used by the plugin).
    if api_base:
        try:
            data = get_json(f"{api_base}/film/{urllib.parse.quote(slug)}", "https://phim.nguonc.com/")
            movie = data.get("movie") or {}
            servers = movie.get("episodes") or data.get("episodes") or []
            for server in servers:
                items = server.get("items") or server.get("server_data") or server.get("list") or []
                for ep in items:
                    embed = ep.get("embed") or ep.get("link_embed")
                    direct = ep.get("link_m3u8") or ep.get("m3u8")
                    if embed or direct:
                        print(f"DETAIL_OK slug={slug} title={movie.get('name','')} server={server.get('server_name') or server.get('name') or ''}")
                        return embed, direct
        except Exception as e:
            print(f"DETAIL_API_FAILED {e}")

    # Website fallback used by current NguonC redesign.
    movie_url = f"https://phim.nguonc.com/phim/{slug}"
    status, html = curl(movie_url, referer="https://phim.nguonc.com/")
    if status != 200:
        raise RuntimeError(f"movie page HTTP {status}")
    m = re.search(r'data-episode-url=["\']([^"\']+)["\']', html, re.I)
    episode_url = resolve(movie_url, m.group(1)) if m else movie_url.rstrip("/") + "/episodes"
    data = get_json(episode_url, movie_url)
    servers = data.get("servers") or []
    for server in servers:
        for ep in server.get("list") or server.get("items") or server.get("server_data") or []:
            embed = ep.get("embed") or ep.get("link_embed")
            direct = ep.get("m3u8") or ep.get("link_m3u8")
            if embed or direct:
                print(f"EPISODES_OK endpoint={episode_url} server={server.get('name') or server.get('server_name') or ''}")
                return embed, direct
    raise RuntimeError("no episode source found")

def aes_gcm_decrypt(key, iv, ciphertext, aad=None):
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except Exception as e:
        raise RuntimeError(f"cryptography unavailable: {e}")
    return AESGCM(key).decrypt(iv, ciphertext, aad)

def absolutize_playlist(text, base):
    out = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            out.append(line)
        elif line.startswith("#"):
            def repl(m):
                return 'URI="' + resolve(base, m.group(1)) + '"'
            out.append(re.sub(r'URI="([^"]+)"', repl, line, flags=re.I))
        else:
            out.append(resolve(base, line))
    return "\n".join(out)

def resolve_stream(embed, direct, movie_url):
    if direct:
        status, playlist = curl(direct, referer=movie_url)
        if status == 200 and "#EXTM3U" in playlist:
            return direct, playlist
        raise RuntimeError(f"direct playlist HTTP {status}")

    if not embed:
        raise RuntimeError("missing embed")

    status, html = curl(
        embed,
        referer=movie_url,
        headers={"Origin": origin(movie_url), "Accept": "text/html,*/*"},
    )
    if status != 200:
        raise RuntimeError(f"embed HTTP {status}")

    # Direct media URL in HTML.
    m = re.search(r'https?:\\?/\\?/[^"\'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"\'\\\s<>]*)?', html, re.I)
    if m:
        url = m.group(0).replace("\\/", "/")
        status2, body2 = curl(url, referer=embed)
        if status2 == 200:
            return url, body2

    # Current StreamC bootstrap flow.
    m = re.search(r'<script[^>]*id=["\']stream-bootstrap["\'][^>]*>(.*?)</script>', html, re.I | re.S)
    if m:
        meta = json.loads(m.group(1))
        api = resolve(embed, meta["api"])
        payload = json.dumps({
            "action": "bootstrap",
            "referrer": movie_url[:4096],
            "frame_origins": [origin(movie_url)],
            "request_grant": True,
            "playlist_format": "aesgcm-v2",
            "pretty_url": True,
            "path_chunks": True,
            "bootstrap_format": "aesgcm-v1",
        }, separators=(",", ":"))
        status3, body3 = curl(
            api,
            referer=embed,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json,text/plain,*/*", "Origin": origin(embed)},
            body=payload,
        )
        if status3 != 200:
            raise RuntimeError(f"bootstrap HTTP {status3}: {body3[:160]}")
        envelope = json.loads(body3)
        opened_text = body3
        if envelope.get("format") == "aesgcm-v1":
            aad = ("stream-bootstrap-v1\n" + api).encode()
            key = hashlib.sha256(aad).digest()
            opened_text = aes_gcm_decrypt(
                key,
                bytes.fromhex(envelope["iv"]),
                base64.b64decode(envelope["data"]),
                aad,
            ).decode("utf-8")
        opened = json.loads(opened_text)
        playlist_url = ((opened.get("preissued") or {}).get("playlist"))
        if not playlist_url:
            raise RuntimeError("bootstrap returned no playlist")
        status4, playlist = curl(playlist_url, referer=embed)
        if status4 != 200:
            raise RuntimeError(f"playlist HTTP {status4}")
        if "#ENC-AESGCM" in playlist:
            ivm = re.search(r'#ENC-AESGCM[^\n]*iv=([0-9a-fA-F]+)', playlist)
            cipher_line = next((x.strip() for x in playlist.splitlines() if x.strip() and not x.startswith("#")), None)
            video_hash = opened.get("video") or (re.search(r'[?&]hash=([0-9a-fA-F]+)', embed) or [None, None])[1]
            if not (ivm and cipher_line and video_hash):
                raise RuntimeError("encrypted playlist metadata incomplete")
            key = hmac.new(b"stream-derive-v1", video_hash.encode(), hashlib.sha256).digest()
            playlist = aes_gcm_decrypt(key, bytes.fromhex(ivm.group(1)), base64.b64decode(cipher_line)).decode("utf-8")
        playlist = absolutize_playlist(playlist, playlist_url)
        if "#EXTM3U" not in playlist:
            raise RuntimeError("resolved playlist is not HLS")
        return playlist_url, playlist

    # Legacy data-obf fallback.
    m = re.search(r'data-obf=["\']([^"\']+)["\']', html, re.I)
    if m:
        legacy = json.loads(base64.b64decode(m.group(1)).decode())
        path = legacy.get("sUb")
        if not path:
            raise RuntimeError("legacy data-obf missing sUb")
        url = path if path.startswith("http") else origin(embed) + "/" + path.lstrip("/")
        if not url.lower().endswith(".m3u8"):
            url += ".m3u8"
        status5, playlist = curl(url, referer=embed)
        if status5 != 200:
            raise RuntimeError(f"legacy playlist HTTP {status5}")
        if "#EXTM3U" in playlist:
            return url, playlist
        raise RuntimeError("legacy playlist is not HLS")

    raise RuntimeError("no supported StreamC player data found")

def verify_first_media(playlist, referer):
    media = None
    for line in playlist.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            media = line
            break
    if not media:
        raise RuntimeError("playlist has no media URI")
    # For a master playlist, the first URI can be another m3u8. Fetch one more level.
    if ".m3u8" in media.lower():
        status, nested = curl(media, referer=referer)
        if status != 200 or "#EXTM3U" not in nested:
            raise RuntimeError(f"nested playlist HTTP {status}")
        for line in nested.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                media = line
                break
    # Fetch only a small prefix. HTTP 200/206 and non-empty bytes prove the media URL is reachable.
    cmd = ["curl", "-sS", "-L", "--compressed", "--max-time", "20", "-A", UA, "-e", referer, "-r", "0-4095", "-o", tempfile.mktemp(), "-w", "%{http_code}:%{size_download}", media]
    p = subprocess.run(cmd, capture_output=True, timeout=30)
    result = p.stdout.decode("utf-8", "replace").strip()
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace").strip())
    code, size = result.split(":", 1)
    if int(code) not in (200, 206) or float(size) <= 0:
        raise RuntimeError(f"media fetch failed {result}")
    print(f"MEDIA_OK http={code} bytes={size}")

def main():
    slug, api_base = find_slug()
    movie_url = f"https://phim.nguonc.com/phim/{slug}"
    embed, direct = find_episode(slug, api_base)
    print(f"SOURCE_OK slug={slug} direct={bool(direct)} embed={bool(embed)}")
    stream_url, playlist = resolve_stream(embed, direct, movie_url)
    print(f"PLAYLIST_OK url={stream_url[:120]} lines={len(playlist.splitlines())}")
    verify_first_media(playlist, embed or movie_url)
    print("RUNTIME_E2E_OK")

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("RUNTIME_E2E_FAIL", type(exc).__name__, str(exc))
        sys.exit(1)
