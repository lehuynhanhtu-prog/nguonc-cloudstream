#!/usr/bin/env python3
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.lagradost.cloudstream3"
ACCOUNT_ACTIVITY = f"{PKG}/.ui.account.AccountSelectActivity2"
REPO_URI = "cloudstreamrepo://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/repo.json"
MOVIE_URL = "https://phim.nguonc.com/phim/kizu-darake-seijo-yori-houfuku-wo-komete-phan-2"
PROXY = "10.0.2.2:8899"

def run(cmd, check=True, timeout=60):
    print("+", " ".join(cmd), flush=True)
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}")
    return p.stdout

def adb(*args, check=True, timeout=60):
    return run(["adb", *args], check=check, timeout=timeout)

def dump_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/window.xml", check=False, timeout=20)
    xml = adb("exec-out", "cat", "/sdcard/window.xml", check=False, timeout=20)
    if "<hierarchy" not in xml:
        return None, xml
    try:
        return ET.fromstring(xml), xml
    except ET.ParseError:
        return None, xml

def bounds_center(bounds):
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)

def find_node(root, *, resource_suffix=None, text=None, desc=None, contains_text=None):
    if root is None:
        return None
    for node in root.iter("node"):
        rid = node.attrib.get("resource-id", "")
        ntext = node.attrib.get("text", "")
        ndesc = node.attrib.get("content-desc", "")
        if resource_suffix and not rid.endswith(resource_suffix):
            continue
        if text is not None and ntext != text:
            continue
        if desc is not None and ndesc != desc:
            continue
        if contains_text is not None and contains_text.lower() not in ntext.lower():
            continue
        if node.attrib.get("visible-to-user", "true") == "false":
            continue
        return node
    return None

def wait_node(timeout=30, **selector):
    end = time.time() + timeout
    last_xml = ""
    while time.time() < end:
        root, xml = dump_ui()
        last_xml = xml
        node = find_node(root, **selector)
        if node is not None:
            return node
        time.sleep(1)
    print("UI_DUMP_TIMEOUT", last_xml[-12000:], flush=True)
    raise RuntimeError(f"UI node not found: {selector}")

def tap_node(node):
    center = bounds_center(node.attrib.get("bounds"))
    if not center:
        raise RuntimeError(f"node has invalid bounds: {node.attrib}")
    adb("shell", "input", "tap", str(center[0]), str(center[1]))
    time.sleep(1)

def wait_log(pattern, timeout=60, since_clear=True):
    rx = re.compile(pattern, re.I | re.S)
    end = time.time() + timeout
    last = ""
    while time.time() < end:
        last = adb("logcat", "-d", "-v", "brief", check=False, timeout=20)
        if rx.search(last):
            return last
        time.sleep(1)
    print("LOGCAT_TIMEOUT_TAIL\n", last[-20000:], flush=True)
    raise RuntimeError(f"log pattern not found: {pattern}")

def ui_has(resource_suffix):
    root, _ = dump_ui()
    return find_node(root, resource_suffix=resource_suffix) is not None

def clear_runtime_permissions(timeout=20):
    # Prefer granting storage permission through adb, then dismiss any remaining
    # Android permission dialogs that CloudStream shows on first launch.
    for perm in (
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    ):
        adb("shell", "pm", "grant", PKG, perm, check=False, timeout=20)

    end = time.time() + timeout
    while time.time() < end:
        root, _ = dump_ui()
        allow = (
            find_node(root, resource_suffix=":id/permission_allow_button")
            or find_node(root, text="Allow")
            or find_node(root, contains_text="While using the app")
        )
        if allow is None:
            return
        tap_node(allow)
        print("ANDROID_PERMISSION_ALLOWED", flush=True)
        time.sleep(1)

def main():
    apk = os.environ.get("CLOUDSTREAM_APK", "/tmp/cloudstream.apk")
    if not os.path.exists(apk):
        raise RuntimeError(f"missing APK: {apk}")

    adb("wait-for-device")
    adb("install", "-r", apk, timeout=120)
    adb("logcat", "-c", check=False)

    # First launch. A fresh install has one default account, so AccountSelectActivity
    # auto-enters MainActivity and shows the one-time setup.
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", timeout=30)
    clear_runtime_permissions()
    try:
        skip = wait_node(timeout=35, resource_suffix=":id/skip_btt")
        tap_node(skip)
        print("ANDROID_SETUP_SKIPPED", flush=True)
    except Exception:
        # If setup was already completed in a reused emulator image, continue.
        print("ANDROID_SETUP_SKIP_NOT_NEEDED", flush=True)

    clear_runtime_permissions(timeout=8)

    # Test the exact released .cs3 as a CloudStream local plugin. This avoids
    # depending on repository-addition UI while exercising CloudStream's real
    # plugin loader and the released artifact byte-for-byte.
    plugin_path = "/tmp/NguonC.cs3"
    run([
        "curl", "-fL", "--retry", "3",
        "https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/NguonC.cs3",
        "-o", plugin_path,
    ], timeout=90)
    adb("shell", "mkdir", "-p", "/sdcard/Cloudstream3/plugins")
    adb("push", plugin_path, "/sdcard/Cloudstream3/plugins/NguonC.cs3", timeout=60)

    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c", check=False)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", timeout=30)
    clear_runtime_permissions(timeout=8)

    logs = wait_log(r"Loaded plugin\s+NguonC\s+successfully", timeout=75)
    if "Failed to load" in logs and "NguonC" in logs:
        raise RuntimeError("CloudStream logged a plugin load failure")
    print("ANDROID_PLUGIN_LOADED", flush=True)

    # Restart CloudStream after changing Android's global proxy so all provider/player
    # clients initialize against the verified VN egress bridge.
    adb("shell", "settings", "put", "global", "http_proxy", PROXY)
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c", check=False)

    # Open a real NguonC title through CloudStream. AccountSelectActivity propagates
    # this VIEW intent into non-exported MainActivity.
    adb(
        "shell", "am", "start", "-W",
        "-n", ACCOUNT_ACTIVITY,
        "-a", "android.intent.action.VIEW",
        "-d", MOVIE_URL,
        timeout=45,
    )

    # The real provider must successfully load metadata/episodes to expose a play button.
    play = None
    end = time.time() + 75
    while time.time() < end:
        root, xml = dump_ui()
        play = (
            find_node(root, resource_suffix=":id/result_play_series_button")
            or find_node(root, resource_suffix=":id/result_play_movie_button")
        )
        if play is not None:
            break
        if find_node(root, resource_suffix=":id/result_loading_error") is not None:
            print("RESULT_ERROR_UI", xml[-12000:], flush=True)
            raise RuntimeError("CloudStream result page entered error state")
        time.sleep(1)
    if play is None:
        raise RuntimeError("real NguonC title did not expose a play button in CloudStream")

    print("ANDROID_PROVIDER_METADATA_AND_EPISODES_OK", flush=True)
    tap_node(play)

    # Confirm that the real CloudStream Media3 player rendered video, not merely
    # that loadLinks returned an URL.
    logs = wait_log(r"CS3IPlayer.*Rendered first frame|Rendered first frame", timeout=90)
    if re.search(r"loadExo error|PlaybackException|Failed link loading", logs, re.I):
        # Only fail when an error is after our movie launch and no first frame marker.
        if not re.search(r"Rendered first frame", logs, re.I):
            raise RuntimeError("player error before first frame")
    if not ui_has(":id/player_view"):
        # UI can hide controls after playback starts, so the log marker is authoritative,
        # but still report whether the PlayerView is present at observation time.
        print("ANDROID_PLAYER_VIEW_NOT_VISIBLE_AT_DUMP", flush=True)
    else:
        print("ANDROID_PLAYER_VIEW_VISIBLE", flush=True)

    print("ANDROID_FIRST_FRAME_RENDERED", flush=True)
    print("ANDROID_CLOUDSTREAM_E2E_OK", flush=True)

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("ANDROID_CLOUDSTREAM_E2E_FAIL", type(exc).__name__, str(exc), flush=True)
        try:
            print(adb("logcat", "-d", "-v", "time", check=False, timeout=20)[-30000:])
        except Exception:
            pass
        sys.exit(1)
