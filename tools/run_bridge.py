"""Bounded USB/ADB prototype: BLE-M3 -> configured foreground media app."""
import argparse
import datetime
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
from profiles import load_profiles, focused_package


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--seconds", type=int, default=180)
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 600:
        parser.error("--seconds must be 1..600")
    root = Path(__file__).resolve().parents[1]
    profiles = load_profiles(args.config or root / "config" / "profiles.json")
    log_dir = root / "logs"
    log_dir.mkdir(exist_ok=True)
    log_path = log_dir / (datetime.datetime.now().strftime("bridge-%Y%m%d-%H%M%S") + ".log")
    adb = [args.adb, "-s", args.serial]
    responses = queue.Queue()
    helper = capture = None
    capture_pid = None
    with log_path.open("w", encoding="utf-8") as log_file:
        def emit(message):
            line = datetime.datetime.now().isoformat(timespec="milliseconds") + " " + message
            print(line, flush=True)
            log_file.write(line + "\n")
            log_file.flush()

        def helper_reader():
            for line in helper.stdout:
                try:
                    responses.put(json.loads(line))
                except ValueError:
                    responses.put({"ok": False, "error": "Unexpected helper output: " + line.strip()})
            responses.put({"ok": False, "error": "Helper exited"})

        def request(command, strict=True):
            helper.stdin.write(command + "\n")
            helper.stdin.flush()
            result = responses.get(timeout=8)
            emit("MEDIA " + json.dumps(result, ensure_ascii=False))
            if strict and not result.get("ok"):
                raise RuntimeError(result.get("error", "Unexpected helper response"))
            return result

        try:
            helper = subprocess.Popen(
                adb + ["shell", "CLASSPATH=/data/local/tmp/ble-youtube-control.jar app_process /system/bin YouTubeControl --stdio"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8", bufsize=1)
            threading.Thread(target=helper_reader, daemon=True).start()
            ready = responses.get(timeout=10)
            if not ready.get("ready"):
                raise RuntimeError(str(ready))
            focus = subprocess.run(adb + ["shell", "dumpsys window | grep mCurrentFocus"],
                                   capture_output=True, text=True, encoding="utf-8", timeout=5)
            selected_package = focused_package(focus.stdout)
            if selected_package not in profiles:
                raise RuntimeError("Open an app with a configured profile before starting")
            request("target " + selected_package)
            baseline = request("status")
            if baseline["state"] not in (2, 3):
                raise RuntimeError("Open a video and pause/play it before starting")
            capture = subprocess.Popen(
                adb + ["shell", "/data/local/tmp/ble_m3_capture", "--listen", "--seconds", str(args.seconds)],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", bufsize=1)
            for line in capture.stdout:
                line = line.strip()
                emit("CAPTURE " + line)
                if line.startswith("PID "):
                    capture_pid = int(line.split()[1])
                if line.startswith("LISTENER_READY"):
                    emit("BRIDGE_READY profiles=" + json.dumps(profiles, ensure_ascii=False) + " log=" + str(log_path))
                match = re.match(r"ACTION (UP|DOWN|LEFT|RIGHT|CENTER|PHOTO)\b", line)
                if not match:
                    continue
                focus = subprocess.run(
                    adb + ["shell", "dumpsys window | grep mCurrentFocus"],
                    capture_output=True, text=True, encoding="utf-8", timeout=5)
                package = focused_package(focus.stdout)
                if package not in profiles:
                    emit("SKIPPED No profile for the focused app")
                    continue
                command = profiles[package]["commands"][match[1]]
                if command is None:
                    emit("UNASSIGNED button=" + match[1])
                    continue
                if package != selected_package:
                    request("target " + package)
                    selected_package = package
                requested = request(command, strict=False)
                if not requested.get("ok"):
                    emit("ACTION_UNAVAILABLE button=" + match[1])
                    continue
                # Request acceptance alone is not evidence of player movement.
                observed = None
                for _ in range(8):
                    time.sleep(0.15)
                    observed = request("status")
                    if "target" in requested:
                        if abs(observed["position"] - requested["target"]) <= 1500:
                            break
                    elif observed["state"] == (2 if requested["requestedState"] == "paused" else 3):
                        break
                emit("RESULT button=" + match[1] + " requested=" + json.dumps(requested) + " observed=" + json.dumps(observed))
            code = capture.wait(timeout=3)
            if code != 0:
                raise RuntimeError("Capture exited with code " + str(code))
        except KeyboardInterrupt:
            emit("STOP requested")
        except Exception as error:
            emit("ERROR " + type(error).__name__ + ": " + str(error))
            raise
        finally:
            if capture is not None and capture.poll() is None:
                if capture_pid is not None:
                    try:
                        subprocess.run(adb + ["shell", "kill", "-TERM", str(capture_pid)], timeout=5, capture_output=True)
                    except (subprocess.SubprocessError, OSError):
                        pass
                try:
                    capture.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    capture.terminate()
            if helper is not None and helper.poll() is None:
                try:
                    helper.stdin.write("quit\n")
                    helper.stdin.flush()
                    helper.wait(timeout=3)
                except (OSError, subprocess.TimeoutExpired):
                    helper.terminate()
            emit("BRIDGE_STOPPED")


if __name__ == "__main__":
    main()
