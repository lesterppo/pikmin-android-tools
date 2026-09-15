#!/usr/bin/env python3
"""ui.py — drive + read an Android screen (works with Unity/native UIs).

  ui.py shot [file]              capture screenshot (default /tmp/ui.png)
  ui.py ocr [file] [lang]        OCR the shot / file -> lines with tap coords
  ui.py find <text> [lang]       locate text, print center x,y (tap target)
  ui.py tap <text> [lang]        find text on screen and tap it
  ui.py tapxy <x> <y>            tap pixel coords (1080x2340 space)
  ui.py focus                   print current focused window (package/activity)
Substring match, case-insensitive; Chinese (zh-TW) supported via chi_tra.
"""
import os, re, subprocess, sys, time

ADB = os.path.expanduser("~/android-sdk/platform-tools/adb")
TESS = os.environ.get("TESSDATA_PREFIX", os.path.expanduser("~/.local/share/tessdata"))


def sh(cmd, timeout=120):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)


def shot(path="/tmp/ui.png"):
    subprocess.run(f"{ADB} exec-out screencap -p > {path}", shell=True, check=True)
    return path


def ocr(path="/tmp/ui.png", lang="eng+chi_tra"):
    env = dict(os.environ, TESSDATA_PREFIX=TESS)
    tsv = subprocess.run(
        ["tesseract", path, "-", "-l", lang, "tsv"],
        capture_output=True, text=True, env=env).stdout
    words = []
    for line in tsv.splitlines()[1:]:
        p = line.split("\t")
        if len(p) < 12:
            continue
        try:
            conf = float(p[10])
        except ValueError:
            continue
        txt = p[11].strip()
        if conf < 40 or not txt:
            continue
        x, y, w, h = int(p[6]), int(p[7]), int(p[8]), int(p[9])
        words.append({"t": txt, "x": x + w // 2, "y": y + h // 2, "box": (x, y, w, h), "c": conf})
    return words


def lines(words):
    """group words into visual lines (y buckets)"""
    out = {}
    for w in words:
        key = round(w["y"] / 35)
        out.setdefault(key, []).append(w)
    res = []
    for k in sorted(out):
        ws = sorted(out[k], key=lambda v: v["x"])
        res.append({
            "text": " ".join(v["t"] for v in ws),
            "y": sum(v["y"] for v in ws) // len(ws),
            "x": sum(v["x"] for v in ws) // len(ws),
            "first": ws[0],
        })
    return res


def focus():
    o = sh(f"{ADB} shell dumpsys window displays | grep -E 'mCurrentFocus' | head -1").stdout
    return o.strip()


def find(text, lang="eng+chi_tra", path=None):
    p = path or shot()
    ws = ocr(p, lang)
    tl = text.lower()
    hits = [w for w in ws if tl in w["t"].lower()]
    if not hits:
        # try joined-line match
        for ln in lines(ws):
            if tl in ln["text"].lower():
                return ln["x"], ln["y"]
        return None
    # pick topmost then leftmost
    hits.sort(key=lambda w: (w["y"], w["x"]))
    return hits[0]["x"], hits[0]["y"]


def tap(x, y):
    sh(f"{ADB} shell input tap {int(x)} {int(y)}")


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); return
    cmd = a[0]
    if cmd == "shot":
        print(shot(a[1] if len(a) > 1 else "/tmp/ui.png"))
    elif cmd == "ocr":
        p = a[1] if len(a) > 1 and a[1].endswith(".png") else shot()
        for ln in lines(ocr(p, a[2] if len(a) > 2 else "eng+chi_tra")):
            print(f"y={ln['y']:>5} x={ln['x']:>5}  {ln['text']}")
    elif cmd == "find":
        r = find(a[1], a[2] if len(a) > 2 else "eng+chi_tra")
        print(f"{r[0]} {r[1]}" if r else "NOTFOUND")
    elif cmd == "tap":
        r = find(a[1], a[2] if len(a) > 2 else "eng+chi_tra")
        if not r:
            print("NOTFOUND"); return
        tap(*r); print(f"tapped {r[0]},{r[1]}")
    elif cmd == "tapxy":
        tap(a[1], a[2]); print(f"tapped {a[1]},{a[2]}")
    elif cmd == "focus":
        print(focus())
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
