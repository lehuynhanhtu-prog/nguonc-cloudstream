#!/usr/bin/env python3
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.lagradost.cloudstream3"

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

def find_node(root, *, resource_suffix=None, text=None, contains_text=None):
    if root is None:
        return None
    for node in root.iter("node"):
        rid = node.attrib.get("resource-id", "")
        ntext = node.attrib.get("text", "")
        if resource_suffix and not rid.endswith(resource_suffix):
            continue
        if text is not None and ntext != text:
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

def raw_bounds(xml, resource_suffix):
    # UiAutomator XML is occasionally parseable for most of the page but misses a
    # newly-rendered button in ElementTree. The raw dump still contains the node.
    # Match resource-id and bounds directly as a fallback.
    rx = re.compile(
        r'<node[^>]*resource-id="[^"]*' + re.escape(resource_suffix) +
        r'"[^>]*bounds="(\\[\\d+,\\d+\\]\\[\\d+,\\d+\\])"'
    )
    m = rx.search(xml or "")
    return m.group(1) if m else None

def tap_bounds(bounds):
    center = bounds_center(bounds)
    if not center:
        raise RuntimeError(f"invalid raw bounds: {bounds}")
    adb("shell", "input", "tap", str(center[0]), str(center[1]))
    time.sleep(1)

def wait_log(pattern, timeout=60):
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

def clear_runtime_permissions(timeout=20):
    for perm in (
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    ):
        adb("shell", "pm", "grant", PKG, perm, check=False, timeout=20)
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
        time.sleep(1)

def finish_first_run_setup(timeout=50):
    end = time.time() + timeout
    while time.time() < end:
        root, _ = dump_ui()
        if find_node(root, resource_suffix=":id/navigation_home") is not None:
            return
        done = find_node(root, resource_suffix=":id/next_btt")
        if done is not None:
            tap_node(done)
            time.sleep(2)
            continue
        skip = find_node(root, resource_suffix=":id/skip_btt")
        if skip is not None:
            tap_node(skip)
            time.sleep(2)
            continue
        time.sleep(1)
    _, xml = dump_ui()
    print("SETUP_TIMEOUT", xml[-12000:], flush=True)
    raise RuntimeError("CloudStream first-run setup did not reach Home")

def main():
    apk = os.environ.get("CLOUDSTREAM_APK", "/tmp/cloudstream.apk")
    plugin_path = os.environ.get("PLUGIN_CS3", "/tmp/nguonc-build/MyStream.cs3")
    if not os.path.exists(apk):
        raise RuntimeError(f"missing APK: {apk}")
    if not os.path.exists(plugin_path):
        raise RuntimeError(f"missing plugin: {plugin_path}")

    adb("wait-for-device")
    adb("install", "-r", apk, timeout=120)
    adb("logcat", "-c", check=False)

    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", timeout=30)
    clear_runtime_permissions()
    try:
        skip = wait_node(timeout=35, resource_suffix=":id/skip_btt")
        tap_node(skip)
    except Exception:
        pass
    clear_runtime_permissions(timeout=8)

    adb("shell", "mkdir", "-p", "/sdcard/Cloudstream3/plugins")
    adb("shell", "rm", "-f", "/sdcard/Cloudstream3/safe", check=False)
    adb("push", plugin_path, "/sdcard/Cloudstream3/plugins/MyStream.cs3", timeout=60)

    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c", check=False)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", timeout=30)
    clear_runtime_permissions(timeout=8)
    finish_first_run_setup()
    time.sleep(8)

    snapshot = adb("logcat", "-d", "-v", "brief", check=False, timeout=20)
    if not re.search(r"Loaded plugin\s+MyStream(?:\.cs3)?\s+successfully", snapshot, re.I):
        interesting = [
            line for line in snapshot.splitlines()
            if re.search(r"PluginManager|MyStream|Loading plugin|Failed to (?:load|copy)", line, re.I)
        ]
        print("PLUGIN_LOG_SNAPSHOT\n" + "\n".join(interesting[-300:]), flush=True)
        raise RuntimeError("MyStream.cs3 was not loaded by CloudStream")
    print("ANDROID_MYSTREAM_PLUGIN_LOADED", flush=True)

    source_button = wait_node(timeout=30, resource_suffix=":id/home_api_fab")
    tap_node(source_button)
    provider_row = wait_node(timeout=30, contains_text="MyStream")
    tap_node(provider_row)
    print("ANDROID_MYSTREAM_PROVIDER_SELECTED", flush=True)

    end_catalog = time.time() + 60
    first_card = None
    last_xml = ""
    while time.time() < end_catalog:
        root, xml = dump_ui()
        last_xml = xml
        source = find_node(root, resource_suffix=":id/home_api_fab")
        card = find_node(root, resource_suffix=":id/background_card")
        loading_error = find_node(root, resource_suffix=":id/home_loading_error")
        if loading_error is not None and loading_error.attrib.get("visible-to-user", "true") != "false":
            print("MYSTREAM_HOME_ERROR", xml[-16000:], flush=True)
            raise RuntimeError("MyStream home entered loading error state")
        if source is not None and "MyStream" in source.attrib.get("text", "") and card is not None:
            first_card = card
            break
        time.sleep(1)

    if first_card is None:
        print("MYSTREAM_CATALOG_TIMEOUT", last_xml[-16000:], flush=True)
        raise RuntimeError("MyStream did not populate CloudStream Home")
    print("ANDROID_MYSTREAM_CATALOG_OK", flush=True)
    tap_node(first_card)

    launch = None
    launch_bounds = None
    launch_label = ""
    end = time.time() + 90
    while time.time() < end:
        root, xml = dump_ui()
        launch = (
            find_node(root, resource_suffix=":id/result_play_movie")
            or find_node(root, resource_suffix=":id/result_play_movie_button")
            or find_node(root, resource_suffix=":id/result_play_series")
            or find_node(root, resource_suffix=":id/result_play_series_button")
            or find_node(root, resource_suffix=":id/episode_text")
            or find_node(root, text="Play Movie")
        )
        if launch is not None:
            launch_label = launch.attrib.get("resource-id", "") or launch.attrib.get("text", "")
            break

        for suffix in (
            ":id/result_play_movie",
            ":id/result_play_movie_button",
            ":id/result_play_series",
            ":id/result_play_series_button",
            ":id/episode_text",
        ):
            launch_bounds = raw_bounds(xml, suffix)
            if launch_bounds:
                launch_label = suffix
                break
        if launch_bounds:
            break

        if find_node(root, resource_suffix=":id/result_loading_error") is not None:
            print("MYSTREAM_RESULT_ERROR", xml[-16000:], flush=True)
            raise RuntimeError("MyStream result page entered error state")
        time.sleep(1)

    if launch is None and launch_bounds is None:
        root, xml = dump_ui()
        launch = (
            find_node(root, resource_suffix=":id/result_play_movie")
            or find_node(root, resource_suffix=":id/result_play_movie_button")
            or find_node(root, resource_suffix=":id/result_play_series")
            or find_node(root, resource_suffix=":id/result_play_series_button")
            or find_node(root, resource_suffix=":id/episode_text")
            or find_node(root, text="Play Movie")
        )
        if launch is not None:
            launch_label = launch.attrib.get("resource-id", "") or launch.attrib.get("text", "")
        else:
            for suffix in (
                ":id/result_play_movie",
                ":id/result_play_movie_button",
                ":id/result_play_series",
                ":id/result_play_series_button",
                ":id/episode_text",
            ):
                launch_bounds = raw_bounds(xml, suffix)
                if launch_bounds:
                    launch_label = suffix
                    break

    if launch is None and launch_bounds is None:
        print("MYSTREAM_RESULT_TIMEOUT", xml[-16000:], flush=True)
        raise RuntimeError("MyStream sample did not expose a play control")

    print("ANDROID_MYSTREAM_PLAY_TARGET", launch_label, launch_bounds or "", flush=True)
    adb("logcat", "-c", check=False)
    if launch is not None:
        tap_node(launch)
    else:
        tap_bounds(launch_bounds)

    logs = wait_log(r"CS3IPlayer.*Rendered first frame|Rendered first frame", timeout=90)
    if re.search(r"loadExo error|PlaybackException|Failed link loading", logs, re.I) and not re.search(r"Rendered first frame", logs, re.I):
        raise RuntimeError("player error before first frame")

    print("ANDROID_MYSTREAM_FIRST_FRAME_RENDERED", flush=True)
    print("ANDROID_MYSTREAM_E2E_OK", flush=True)

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("ANDROID_MYSTREAM_E2E_FAIL", type(exc).__name__, str(exc), flush=True)
        try:
            print(adb("logcat", "-d", "-v", "time", check=False, timeout=20)[-30000:])
        except Exception:
            pass
        sys.exit(1)
