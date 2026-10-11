#!/usr/bin/env python3
"""Drive the phone UI over ADB for on-device tests.

  ui.py tap "Text"            tap the first element whose text/desc contains Text (scrolls to find it)
  ui.py dump                  print visible texts
  ui.py shot file.png         screenshot
"""
import re
import subprocess
import sys
import time

SERIAL = []  # e.g. ["-s", "<adb serial>"]


def adb(*a, binary=False):
    r = subprocess.run(["adb", *SERIAL, *a], capture_output=True)
    return r.stdout if binary else r.stdout.decode(errors="replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    for m in re.finditer(r'<node [^>]*?(?:text="([^"]*)")[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, d, x1, y1, x2, y2 = m.groups()
        yield (t or d), (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2


def tap(text, scrolls=8):
    # look on screen, then scroll down, then all the way up again
    moves = [None] + ["down"] * scrolls + ["up"] * (scrolls * 2)
    for mv in moves:
        if mv == "down":
            adb("shell", "input", "swipe", "540", "1800", "540", "1000", "300")
        elif mv == "up":
            adb("shell", "input", "swipe", "540", "1000", "540", "1800", "300")
        if mv:
            time.sleep(0.6)
        for t, x, y in nodes():
            if text.lower() in t.lower() and 0 < y < 2300:
                adb("shell", "input", "tap", str(x), str(y))
                return True
    return False


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap":
        ok = tap(sys.argv[2])
        print("tapped" if ok else "NOT FOUND: " + sys.argv[2])
        sys.exit(0 if ok else 1)
    if cmd == "dump":
        for t, x, y in nodes():
            if t.strip():
                print(f"{x:5},{y:5}  {t}")
    if cmd == "shot":
        open(sys.argv[2], "wb").write(adb("exec-out", "screencap", "-p", binary=True))
