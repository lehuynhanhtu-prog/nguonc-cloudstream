#!/usr/bin/env python3
import base64
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

    # CloudStream declares MANAGE_EXTERNAL_STORAGE specifically for its plugin API.
    # On Android 11+ it is controlled by AppOps, not by pm grant.
    adb("shell", "appops", "set", PKG, "MANAGE_EXTERNAL_STORAGE", "allow", check=False, timeout=20)

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

def finish_first_run_setup(timeout=35):
    end = time.time() + timeout
    while time.time() < end:
        root, _ = dump_ui()
        if find_node(root, resource_suffix=":id/navigation_home") is not None:
            return
        done = find_node(root, resource_suffix=":id/next_btt")
        if done is not None:
            tap_node(done)
            print("ANDROID_SETUP_NEXT_DONE", flush=True)
            time.sleep(2)
            continue
        skip = find_node(root, resource_suffix=":id/skip_btt")
        if skip is not None:
            tap_node(skip)
            print("ANDROID_SETUP_SKIPPED", flush=True)
            time.sleep(2)
            continue
        time.sleep(1)
    root, xml = dump_ui()
    print("SETUP_TIMEOUT", xml[-12000:], flush=True)
    raise RuntimeError("CloudStream first-run setup did not reach Home")

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
    plugin_path = os.environ.get("PLUGIN_CS3", "/tmp/nguonc-build/NguonC.cs3")
    if not os.path.exists(plugin_path):
        raise RuntimeError(f"missing built plugin: {plugin_path}")
    adb("shell", "mkdir", "-p", "/sdcard/Cloudstream3/plugins")
    adb("shell", "rm", "-f", "/sdcard/Cloudstream3/safe", check=False)
    adb("push", plugin_path, "/sdcard/Cloudstream3/plugins/NguonC.cs3", timeout=60)
    print(adb("shell", "ls", "-l", "/sdcard/Cloudstream3/plugins", check=False), flush=True)
    print(adb("shell", "appops", "get", PKG, "MANAGE_EXTERNAL_STORAGE", check=False), flush=True)

    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c", check=False)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", timeout=30)
    clear_runtime_permissions(timeout=8)

    # Finish any remaining first-run setup pages (notably the Extensions/Done page)
    # and confirm we actually reached MainActivity/Home before judging plugin loading.
    finish_first_run_setup(timeout=50)
    print("ANDROID_HOME_READY", flush=True)
    time.sleep(8)

    app_plugin_dir = "/sdcard/Android/data/com.lagradost.cloudstream3/files/plugins"
    print("APP_PLUGIN_DIR", adb("shell", "ls", "-la", app_plugin_dir, check=False), flush=True)

    snapshot = adb("logcat", "-d", "-v", "brief", check=False, timeout=20)
    interesting = [
        line for line in snapshot.splitlines()
        if re.search(r"PluginManager|NguonC|Loading plugin|Failed to (?:load|copy)|Files in '.+plugins", line, re.I)
    ]
    print("PLUGIN_LOG_SNAPSHOT\n" + "\n".join(interesting[-300:]), flush=True)

    if not re.search(r"Loaded plugin\s+NguonC(?:\.cs3)?\s+successfully", snapshot, re.I):
        raise RuntimeError("released NguonC.cs3 was not loaded by CloudStream; see PLUGIN_LOG_SNAPSHOT")
    print("ANDROID_PLUGIN_LOADED", flush=True)

    # Explicitly clear any emulator/system proxy: this job verifies direct playback.
    adb("shell", "settings", "delete", "global", "http_proxy", check=False)
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c", check=False)

    # Start normally first and wait for the local plugin to be loaded. Previous E2E
    # attempts sent the movie VIEW intent immediately after process start, racing the
    # asynchronous plugin loader and leaving MainActivity with no matching provider.
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", timeout=30)
    clear_runtime_permissions(timeout=8)
    finish_first_run_setup(timeout=40)
    wait_log(r"Loaded plugin\s+NguonC(?:\.cs3)?\s+successfully", timeout=45)
    print("ANDROID_PLUGIN_RELOADED_DIRECT", flush=True)

    # Drive the same UI path as a real user: choose NguonC as the Home source,
    # wait for its live catalog, and open the first real card. This avoids all
    # deep-link routing behavior and proves the provider is usable from CloudStream UI.
    source_button = wait_node(timeout=30, resource_suffix=":id/home_api_fab")
    tap_node(source_button)
    provider_row = wait_node(timeout=30, contains_text="NguonC")
    tap_node(provider_row)
    print("ANDROID_HOME_PROVIDER_SELECTED", flush=True)

    # Wait until the source button reflects the provider and a real movie card appears.
    end_catalog = time.time() + 90
    first_card = None
    last_xml = ""
    while time.time() < end_catalog:
        root, xml = dump_ui()
        last_xml = xml
        source = find_node(root, resource_suffix=":id/home_api_fab")
        card = find_node(root, resource_suffix=":id/background_card")
        loading_error = find_node(root, resource_suffix=":id/home_loading_error")
        if loading_error is not None and loading_error.attrib.get("visible-to-user", "true") != "false":
            print("ANDROID_HOME_PROVIDER_ERROR", xml[-20000:], flush=True)
            raise RuntimeError("NguonC home source entered loading error state")
        if source is not None and "NguonC" in source.attrib.get("text", "") and card is not None:
            first_card = card
            break
        time.sleep(1)

    if first_card is None:
        print("ANDROID_HOME_CATALOG_TIMEOUT", last_xml[-20000:], flush=True)
        raise RuntimeError("NguonC did not populate CloudStream Home catalog")

    print("ANDROID_HOME_CATALOG_OK", flush=True)
    tap_node(first_card)

    # The result can be a movie (single Play button) or a series (episode rows).
    # For series, tapping the first episode is the real user path into loadLinks().
    launch = None
    end = time.time() + 75
    while time.time() < end:
        root, xml = dump_ui()
        launch = (
            find_node(root, resource_suffix=":id/result_play_movie_button")
            or find_node(root, resource_suffix=":id/result_play_series_button")
            or find_node(root, resource_suffix=":id/episode_holder")
        )
        if launch is not None:
            break
        if find_node(root, resource_suffix=":id/result_loading_error") is not None:
            print("RESULT_ERROR_UI", xml[-12000:], flush=True)
            raise RuntimeError("CloudStream result page entered error state")
        time.sleep(1)
    if launch is None:
        root, xml = dump_ui()
        print("RESULT_PAGE_TIMEOUT_UI", xml[-20000:], flush=True)
        print("FOCUSED_ACTIVITY", adb("shell", "dumpsys", "activity", "activities", check=False, timeout=20)[-12000:], flush=True)
        raise RuntimeError("real NguonC title did not expose a movie play button or episode row")

    print("ANDROID_PROVIDER_METADATA_AND_EPISODES_OK", flush=True)
    tap_node(launch)

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
