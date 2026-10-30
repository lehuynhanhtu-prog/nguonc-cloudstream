import base64
import hashlib
import hmac
import json
import os
import re
import sys
from urllib.parse import urljoin, urlparse

import httpx
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)
BASES = [
    "https://phim.nguonc.com",
    "https://nguonc.com",
]

def origin(url: str) -> str:
    p = urlparse(url)
    return f"{p.scheme}://{p.netloc}"

def client():
    return httpx.Client(
        http2=True,
        follow_redirects=True,
        timeout=25.0,
        headers={
            "User-Agent": UA,
            "Accept-Language": "vi-VN,vi;q=0.9,en;q=0.7",
            "Accept-Encoding": "gzip, deflate, br",
        },
    )

def fetch_json(c, url, referer=None):
    headers = {"Accept": "application/json,text/plain,*/*"}
    if referer:
        headers["Referer"] = referer
    r = c.get(url, headers=headers)
    print(f"GET {url} -> {r.status_code} HTTP/{r.http_version}")
    r.raise_for_status()
    return r.json()

def find_working_base(c):
    last = None
    for base in BASES:
        try:
            data = fetch_json(c, f"{base}/api/films/phim-moi-cap-nhat?page=1", base + "/")
            if data.get("status") == "success" and data.get("items"):
                return base, data
        except Exception as e:
            last = e
            print(f"BASE_FAIL {base}: {e}")
    raise RuntimeError(f"No NguonC API base worked: {last}")

def first_embed_from_detail(detail):
    movie = detail.get("movie") or {}
    servers = movie.get("episodes") or detail.get("episodes") or []
    for server in servers:
        items = server.get("items") or server.get("server_data") or server.get("list") or []
        for ep in items:
            embed = (ep.get("embed") or ep.get("link_embed") or "").strip()
            direct = (ep.get("link_m3u8") or ep.get("m3u8") or "").strip()
            if embed or direct:
                return {
                    "server": server.get("server_name") or server.get("name") or "NguonC",
                    "name": ep.get("name") or ep.get("slug") or "episode",
                    "embed": embed,
                    "direct": direct,
                }
    return None

def episode_from_web(c, movie_url):
    r = c.get(movie_url, headers={"Referer": origin(movie_url) + "/", "Accept": "text/html,*/*"})
    print(f"GET {movie_url} -> {r.status_code} HTTP/{r.http_version}")
    r.raise_for_status()
    html = r.text
    m = re.search(r'data-episode-url=["\']([^"\']+)["\']', html, re.I)
    ep_url = urljoin(movie_url, m.group(1)) if m else movie_url.rstrip("/") + "/episodes"
    data = fetch_json(c, ep_url, movie_url)
    for server in data.get("servers") or []:
        items = server.get("list") or server.get("items") or server.get("server_data") or []
        for ep in items:
            embed = (ep.get("embed") or ep.get("link_embed") or "").strip()
            direct = (ep.get("link_m3u8") or ep.get("m3u8") or "").strip()
            if embed or direct:
                return {
                    "server": server.get("server_name") or server.get("name") or "NguonC",
                    "name": ep.get("name") or ep.get("slug") or "episode",
                    "embed": embed,
                    "direct": direct,
                }
    return None

def decrypt_bootstrap(envelope, api_url):
    aad = ("stream-bootstrap-v1\n" + api_url).encode()
    key = hashlib.sha256(aad).digest()
    iv = bytes.fromhex(envelope["iv"])
    data = base64.b64decode(envelope["data"])
    return AESGCM(key).decrypt(iv, data, aad).decode()

def decrypt_playlist(text, video_hash):
    m = re.search(r"#ENC-AESGCM[^\n]*iv=([0-9a-fA-F]+)", text)
    if not m:
        return text
    payload = next(
        (line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")),
        None,
    )
    if not payload:
        raise RuntimeError("Encrypted playlist has no payload")
    key = hmac.new(b"stream-derive-v1", video_hash.encode(), hashlib.sha256).digest()
    clear = AESGCM(key).decrypt(bytes.fromhex(m.group(1)), base64.b64decode(payload), None)
    return clear.decode()

def absolutize_playlist(text, base_url):
    out = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            out.append(line)
        elif line.startswith("#"):
            line = re.sub(
                r'URI="([^"]+)"',
                lambda m: f'URI="{urljoin(base_url, m.group(1))}"',
                line,
                flags=re.I,
            )
            out.append(line)
        else:
            out.append(urljoin(base_url, line))
    return "\n".join(out)

def probe_segment(c, playlist, referer):
    urls = [x.strip() for x in playlist.splitlines() if x.strip() and not x.startswith("#")]
    if not urls:
        raise RuntimeError("Playlist contains no media URI")
    url = urls[0]
    r = c.get(
        url,
        headers={
            "Referer": referer,
            "Range": "bytes=0-1023",
            "Accept": "*/*",
        },
    )
    print(f"SEGMENT {url} -> {r.status_code} HTTP/{r.http_version} bytes={len(r.content)}")
    if r.status_code not in (200, 206) or not r.content:
        raise RuntimeError(f"Segment probe failed with HTTP {r.status_code}")

def resolve_embed(c, embed_url, movie_url):
    headers = {
        "Referer": movie_url,
        "Origin": origin(movie_url),
        "Accept": "text/html,*/*",
    }
    r = c.get(embed_url, headers=headers)
    print(f"EMBED {embed_url} -> {r.status_code} HTTP/{r.http_version}")
    r.raise_for_status()
    html = r.text

    direct = re.search(r'https?:\\?/\\?/[^"\'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"\'\\\s<>]*)?', html, re.I)
    if direct:
        url = direct.group(0).replace("\\/", "/")
        print("DIRECT_MEDIA", url)
        if ".m3u8" in url.lower():
            p = c.get(url, headers={"Referer": embed_url, "Accept": "*/*"})
            p.raise_for_status()
            playlist = absolutize_playlist(p.text, url)
            if "#EXTM3U" not in playlist:
                raise RuntimeError("Direct HLS URL did not return M3U8")
            probe_segment(c, playlist, embed_url)
        return

    m = re.search(
        r'<script[^>]+id=["\']stream-bootstrap["\'][^>]*>(.*?)</script>',
        html,
        re.I | re.S,
    )
    if m:
        meta = json.loads(m.group(1))
        api_url = urljoin(embed_url, meta["api"])
        body = {
            "action": "bootstrap",
            "referrer": movie_url[:4096],
            "frame_origins": [origin(movie_url)],
            "request_grant": True,
            "playlist_format": "aesgcm-v2",
            "pretty_url": True,
            "path_chunks": True,
            "bootstrap_format": "aesgcm-v1",
        }
        br = c.post(
            api_url,
            json=body,
            headers={
                "Origin": origin(embed_url),
                "Referer": embed_url,
                "Accept": "application/json,text/plain,*/*",
            },
        )
        print(f"BOOTSTRAP {api_url} -> {br.status_code} HTTP/{br.http_version}")
        br.raise_for_status()
        envelope = br.json()
        opened = json.loads(
            decrypt_bootstrap(envelope, api_url)
            if envelope.get("format") == "aesgcm-v1"
            else br.text
        )
        playlist_url = ((opened.get("preissued") or {}).get("playlist") or "").strip()
        if not playlist_url:
            raise RuntimeError("Bootstrap returned no preissued playlist")

        pr = c.get(playlist_url, headers={"Referer": embed_url, "Accept": "*/*"})
        print(f"PLAYLIST {playlist_url} -> {pr.status_code} HTTP/{pr.http_version}")
        pr.raise_for_status()
        playlist = pr.text
        if "#ENC-AESGCM" in playlist:
            video_hash = opened.get("video") or ""
            if not video_hash:
                hm = re.search(r"[?&]hash=([0-9a-fA-F]+)", embed_url)
                video_hash = hm.group(1) if hm else ""
            if not video_hash:
                raise RuntimeError("Encrypted playlist but no video hash")
            playlist = decrypt_playlist(playlist, video_hash)
        playlist = absolutize_playlist(playlist, playlist_url)
        if "#EXTM3U" not in playlist:
            raise RuntimeError("Decrypted playlist is not M3U8")
        print("M3U8_OK lines=", len(playlist.splitlines()))
        probe_segment(c, playlist, embed_url)
        return

    obf = re.search(r'data-obf=["\']([^"\']+)["\']', html, re.I)
    if obf:
        obj = json.loads(base64.b64decode(obf.group(1)).decode())
        path = obj.get("sUb") or ""
        if not path:
            raise RuntimeError("Legacy data-obf contains no sUb")
        url = path if path.startswith("http") else origin(embed_url) + "/" + path.lstrip("/")
        if not url.lower().endswith(".m3u8"):
            url += ".m3u8"
        pr = c.get(url, headers={"Referer": embed_url, "Accept": "*/*"})
        print(f"LEGACY_PLAYLIST {url} -> {pr.status_code} HTTP/{pr.http_version}")
        pr.raise_for_status()
        playlist = pr.text
        if "#ENC-AESGCM" in playlist:
            playlist = decrypt_playlist(playlist, obj.get("hD") or "")
        playlist = absolutize_playlist(playlist, url)
        if "#EXTM3U" not in playlist:
            raise RuntimeError("Legacy playlist is not M3U8")
        probe_segment(c, playlist, embed_url)
        return

    raise RuntimeError("Embed page had no direct media, bootstrap, or data-obf")

def main():
    with client() as c:
        base, latest = find_working_base(c)
        item = latest["items"][0]
        slug = item["slug"]
        movie_url = f"{base}/phim/{slug}"
        detail = fetch_json(c, f"{base}/api/film/{slug}", base + "/")
        ep = first_embed_from_detail(detail) or episode_from_web(c, movie_url)
        if not ep:
            raise RuntimeError("No live episode/embed/direct source found")
        print("SELECTED", json.dumps({"slug": slug, **ep}, ensure_ascii=False))
        if ep["direct"]:
            r = c.get(ep["direct"], headers={"Referer": movie_url, "Accept": "*/*"})
            print(f"DIRECT_HLS {ep['direct']} -> {r.status_code} HTTP/{r.http_version}")
            r.raise_for_status()
            playlist = absolutize_playlist(r.text, ep["direct"])
            if "#EXTM3U" not in playlist:
                raise RuntimeError("Direct stream is not M3U8")
            probe_segment(c, playlist, movie_url)
        else:
            resolve_embed(c, ep["embed"], movie_url)

    print("LIVE_RUNTIME_TEST_PASS")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("LIVE_RUNTIME_TEST_FAIL", repr(e))
        raise
