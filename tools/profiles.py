"""App-specific declarative mappings, independent from BLE event decoding."""
import json
from pathlib import Path
import re

BUTTONS = frozenset({"UP", "DOWN", "LEFT", "RIGHT", "CENTER", "PHOTO"})
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\Z")


def action_command(action):
    if not isinstance(action, dict):
        raise ValueError("Each button action must be an object")
    kind = action.get("action")
    if kind == "seek":
        seconds = action.get("seconds")
        if type(seconds) is not int or seconds == 0 or not -300 <= seconds <= 300:
            raise ValueError("seek seconds must be a nonzero integer from -300 to 300")
        return "seek " + str(seconds * 1000)
    if kind == "toggle":
        return "toggle"
    if kind == "none":
        return None
    raise ValueError("Unsupported action: " + str(kind))


def load_profiles(path):
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if data.get("version") != 1 or not isinstance(data.get("profiles"), list):
        raise ValueError("Expected profiles format version 1")
    result = {}
    for profile in data["profiles"]:
        package = profile.get("package", "")
        if not PACKAGE.fullmatch(package) or package in result:
            raise ValueError("Invalid or duplicate package: " + package)
        buttons = profile.get("buttons")
        if not isinstance(buttons, dict) or set(buttons) != BUTTONS:
            raise ValueError("Each profile must define all six buttons")
        result[package] = {
            "name": profile.get("name", package),
            "commands": {key: action_command(value) for key, value in buttons.items()},
        }
    if not result:
        raise ValueError("Configure at least one app profile")
    return result


def focused_package(window_dump):
    for line in window_dump.splitlines():
        if "mCurrentFocus=" in line:
            match = re.search(r"\b([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/", line)
            if match:
                return match.group(1)
    return None
