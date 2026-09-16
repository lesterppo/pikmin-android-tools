#!/usr/bin/env python3
"""
PikminBot Repair — one-click mock-location repair for PikminBot Tools.

Why this exists: switching Developer options off and on clears Android's single
"Select mock location app" slot, which returns the MOCK_LOCATION appop to its
default (deny) while the `mock_location` setting can still name the app. The
phone's settings screen then looks correct while every injection is refused.
Only a shell can flip an appop, so the repair needs adb (wireless debugging).

This app speaks adb over wireless debugging and:

  * finds the phone by itself  (cached endpoint -> mDNS -> LAN port scan)
  * repairs the slot in one click (appop allow + selection + engine re-arm)
  * re-arms the last pin / jog so nothing has to be re-entered on the phone
  * can optionally watch and auto-repair while it stays open (default OFF)

Modes:
    pikmin-repair.py               GUI (default)
    pikmin-repair.py --status      one-shot status, human readable
    pikmin-repair.py --status --json
    pikmin-repair.py --repair      repair now (headless)
    pikmin-repair.py --connect     connect only, print the serial
    pikmin-repair.py --discover    discovery only
    pikmin-repair.py --stop        stop the engine (release mock GPS)
    pikmin-repair.py --pair IP:PORT CODE   pair a new wireless-debug endpoint

Config: ~/.config/pikmin-repair/config.json (endpoint, last pin, package).
"""

import argparse
import concurrent.futures
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

APP = "PikminBot Repair"
VERSION = "1.0"
PKG_DEFAULT = "com.pikminbot.tools"
PORT_LO = 30000          # Android wireless-debugging ephemeral range
PORT_HI = 49999
SCAN_WORKERS = 700
SCAN_TIMEOUT = 0.25
ADB_TIMEOUT = 25

CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "pikmin-repair"
CONFIG = CONFIG_DIR / "config.json"
CACHE_DIR = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "pikmin-repair"
RUN_LOG = CACHE_DIR / "last-run.log"


# --------------------------------------------------------------------- utils
def log(msg):
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    try:                       # keep a copy on disk: easy to attach to a bug report
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        with RUN_LOG.open("a") as fh:
            fh.write(line + "\n")
    except Exception:
        pass


def find_adb():
    cand = []
    env = os.environ.get("ADB_BIN")
    if env:
        cand.append(Path(env))
    for base in (os.environ.get("ANDROID_SDK"), os.environ.get("ANDROID_HOME"),
                 Path.home() / "android-sdk"):
        if base:
            cand.append(Path(base) / "platform-tools" / "adb")
    w = shutil.which("adb")
    if w:
        cand.append(Path(w))
    for c in cand:
        if c and Path(c).exists():
            return str(c)
    return None


ADB = find_adb()


def load_config():
    try:
        return json.loads(CONFIG.read_text())
    except Exception:
        return {}


def save_config(cfg):
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG.write_text(json.dumps(cfg, indent=2))
    except Exception as e:
        log(f"config save failed: {e}")


def run(cmd, timeout=ADB_TIMEOUT):
    """Run a command, return (rc, stdout+stderr)."""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except FileNotFoundError:
        return 127, f"not found: {cmd[0]}"
    except Exception as e:                                    # pragma: no cover
        return 1, str(e)


def adb(*args, serial=None, timeout=ADB_TIMEOUT):
    if not ADB:
        return 127, "adb binary not found (set ADB_BIN or install platform-tools)"
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return run(cmd, timeout=timeout)


def sh(cmd, serial=None, timeout=ADB_TIMEOUT):
    return adb("shell", cmd, serial=serial, timeout=timeout)


# ------------------------------------------------------------------ discovery
def devices():
    """[(serial, state)] for every transport adb knows about."""
    rc, out = adb("devices")
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and "\t" not in line[:4]:
            rows.append((parts[0], parts[1]))
    return rows


def cleanup_offline():
    """Port scans leave `offline` transports behind; a bare `adb shell`/`adb
    connect` then fails with "more than one device/emulator"."""
    killed = []
    for s, st in devices():
        if st in ("offline", "unauthorized"):
            adb("disconnect", s)
            killed.append(s)
    if killed:
        time.sleep(0.3)
    return killed


def live_serial():
    for s, st in devices():
        if st == "device":
            return s
    return None


def mdns_candidates():
    rc, out = adb("mdns", "services")
    found = []
    for line in out.splitlines():
        if "_adb-tls-connect._tcp" in line or "_adb._tcp" in line:
            m = re.search(r"([0-9a-fA-F:.]+):(\d+)", line)
            if m:
                found.append(m.group(0))
    return found


def port_open(host, port):
    s = socket.socket()
    s.settimeout(SCAN_TIMEOUT)
    try:
        s.connect((host, port))
        return port
    except Exception:
        return None
    finally:
        s.close()


def scan_ports(host, lo=PORT_LO, hi=PORT_HI, progress=None):
    """Open TCP ports in the wireless-debugging range (this is how the phone's
    rotating debug port is found again after it changes)."""
    open_ports = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=SCAN_WORKERS) as ex:
        futs = {ex.submit(port_open, host, p): p for p in range(lo, hi + 1)}
        for i, f in enumerate(concurrent.futures.as_completed(futs), 1):
            if progress and i % 4000 == 0:
                progress(i, hi - lo + 1)
            r = f.result()
            if r:
                open_ports.append(r)
    return sorted(open_ports)


def try_connect(serial):
    rc, out = adb("connect", serial, timeout=15)
    time.sleep(0.4)
    for s, st in devices():
        if s == serial and st == "device":
            return True
    return False


def lan_hosts():
    """Neighbour IPs the kernel currently considers reachable."""
    hosts = []
    rc, out = run(["ip", "neigh"])
    for line in out.splitlines():
        parts = line.split()
        if not parts or not re.match(r"^\d+\.\d+\.\d+\.\d+$", parts[0]):
            continue
        up = line.upper()
        if "FAILED" in up or "INCOMPLETE" in up:
            continue
        hosts.append(parts[0])
    return hosts


def phone_ip(cfg):
    """Where to look for the phone: the cached IP, else a LAN neighbour."""
    ip = cfg.get("phone_ip")
    if ip:
        return ip
    hosts = lan_hosts()
    return hosts[0] if hosts else None


def discover(cfg, progress=None, serial_hint=None):
    """Return a connected serial or None. Order: cached -> mDNS -> port scan."""
    cleanup_offline()
    s = serial_hint or live_serial()
    if s:
        return s
    for cand in mdns_candidates():
        if progress:
            progress(f"mDNS candidate {cand}")
        if try_connect(cand):
            return cand
    cached = cfg.get("serial")
    if cached and try_connect(cached):
        return cached
    ip = phone_ip(cfg)
    if not ip:
        return None
    for cand in [f"{ip}:5555"]:
        if try_connect(cand):
            return cand
    if progress:
        progress(f"scanning {ip} ports {PORT_LO}-{PORT_HI} (port rotates)")
    for p in scan_ports(ip, progress=lambda d, t: progress and progress(f"  scanned {d}/{t}")):
        cand = f"{ip}:{p}"
        if try_connect(cand):
            return cand
        adb("disconnect", cand)
    return None


def pair(cfg, endpoint, code):
    rc, out = adb("pair", endpoint, code, timeout=25)
    ok = "Successfully paired" in out
    if ok:
        ip = endpoint.split(":")[0]
        cfg["phone_ip"] = ip
        save_config(cfg)
    return ok, out.strip()


# --------------------------------------------------------------------- status
def parse_appops(out):
    m = re.search(r"MOCK_LOCATION:\s*(\w+)", out)
    return m.group(1) if m else "unknown"


def status(serial, cfg):
    pkg = cfg.get("package", PKG_DEFAULT)
    st = {
        "serial": serial,
        "package": pkg,
        "connected": False,
        "model": None, "android": None, "version": None,
        "dev_options": None, "appop": None, "selected": None,
        "engine": False, "mock_live": False, "fix": None, "degraded": False,
        "healthy": False, "checked_at": datetime.now().isoformat(timespec="seconds"),
        "error": None,
    }
    if not serial:
        st["error"] = "no device connected"
        return st
    rc, out = sh("getprop ro.product.model; getprop ro.build.version.release", serial=serial)
    if rc != 0 or not out.strip():
        st["error"] = f"device unreachable ({out.strip()[:60]})"
        return st
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    st["connected"] = True
    st["model"] = lines[0] if lines else "?"
    st["android"] = lines[1] if len(lines) > 1 else "?"

    cmds = {
        "degraded": "dumpsys notification --noredact 2>/dev/null | grep -c 'Mock location lost'",
        "appop": f"appops get {pkg} android:mock_location 2>&1 | head -1",
        "selected": "settings get secure mock_location",
        "dev_options": "settings get global development_settings_enabled",
        "version": f"dumpsys package {pkg} 2>/dev/null | grep -m1 versionName",
        "engine": f"dumpsys activity services {pkg} 2>/dev/null | grep -c '{pkg}/.EngineService'",
        "provider": "dumpsys location 2>/dev/null | grep -c 'gps provider \\[mock\\]'",
        "fix": "dumpsys location 2>/dev/null | grep -m1 'last location=Location\\[gps' ",
    }
    outs = {}
    for k, c in cmds.items():
        rc, o = sh(c, serial=serial)
        outs[k] = o
    st["appop"] = parse_appops(outs["appop"])
    st["selected"] = (outs["selected"] or "").strip()
    st["dev_options"] = (outs["dev_options"] or "").strip() == "1"
    vm = re.search(r"versionName=(\S+)", outs["version"])
    st["version"] = vm.group(1) if vm else None
    try:
        st["engine"] = int((outs["engine"] or "0").strip().splitlines()[-1]) > 0
    except Exception:
        st["engine"] = False
    try:
        st["mock_live"] = int((outs["provider"] or "0").strip().splitlines()[-1]) > 0
    except Exception:
        st["mock_live"] = False
    fx = re.search(r"Location\[gps ([-\d.]+),([-\d.]+).*?(mock)?\]", outs["fix"] or "")
    if fx:
        st["fix"] = f"{fx.group(1)},{fx.group(2)}" + (" (mock)" if fx.group(3) else "")
    try:
        st["degraded"] = int((outs["degraded"] or "0").strip().splitlines()[-1]) > 0
    except Exception:
        st["degraded"] = False
    st["healthy"] = st["appop"] == "allow" and st["selected"] == pkg
    if not st["healthy"]:
        st["error"] = ("MOCK_LOCATION appop denied — mock location slot was reset "
                       "(Developer options toggle / reinstall)")
    return st


# --------------------------------------------------------------------- repair
def repair(serial, cfg, rearm=True, stop_first=False, emit=None):
    """One-click repair. Returns a result dict with a step log."""
    pkg = cfg.get("package", PKG_DEFAULT)
    res = {"ok": False, "steps": [], "status": None}

    def step(name, ok, detail=""):
        res["steps"].append({"step": name, "ok": ok, "detail": detail})
        if emit:
            emit(f"{'ok  ' if ok else 'FAIL'}  {name} {detail}")

    if not serial:
        step("connect", False, "no device")
        return res

    if stop_first:
        sh(f"am force-stop {pkg}", serial=serial)
        step("force-stop", True, "engine stopped (clean slate)")
        time.sleep(0.5)

    rc, out = sh(f"appops set {pkg} android:mock_location allow", serial=serial)
    step("appop allow", rc == 0, out.strip()[:80])

    rc, out = sh(f"settings put secure mock_location {pkg}", serial=serial)
    step("select as mock app", rc == 0, out.strip()[:80])

    time.sleep(1.0)
    st = status(serial, cfg)
    res["status"] = st
    if st.get("error"):                     # device may have dropped mid-repair
        step("verify", False, st["error"])
        return res
    if not st["healthy"]:
        step("verify", False, f"appop={st['appop']} selected={st['selected']}")
        return res
    step("verify", True, "appop allow + selected")

    if rearm:
        ok, detail = arm_engine(serial, cfg)
        step(f"re-arm {cfg.get('last_mode', 'pin')}", ok, detail)
    res["ok"] = True
    return res


def stop_engine(serial, cfg):
    pkg = cfg.get("package", PKG_DEFAULT)
    sh(f"am force-stop {pkg}", serial=serial)
    time.sleep(0.6)
    rc, out = sh("dumpsys location 2>/dev/null | grep -c 'gps provider \\[mock\\]'", serial=serial)
    return {"ok": True, "provider_left": (out or "0").strip()}


def _line_epoch(line):
    """Epoch seconds for a logcat line ('09-17 02:33:44.713 ...'), or 0."""
    m = re.search(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+)", line)
    if not m:
        return 0.0
    mo, d, h, mi, sec = (int(m.group(i)) for i in range(1, 6))
    try:
        return datetime(datetime.now().year, mo, d, h, mi, sec).timestamp()
    except ValueError:
        return 0.0


def _line_age_s(line):
    """Seconds since a logcat line was written (lines look like
    '09-17 02:33:44.713  12577 ...')."""
    m = re.search(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+)", line)
    if not m:
        return 9999.0
    mo, d, h, mi, sec = (int(m.group(i)) for i in range(1, 6))
    try:
        ts = datetime(datetime.now().year, mo, d, h, mi, sec)
    except ValueError:
        return 9999.0
    if ts > datetime.now():                 # year boundary
        ts = ts.replace(year=ts.year - 1)
    return (datetime.now() - ts).total_seconds()


def remember_pin(cfg, serial, max_age_s=20.0):
    """Learn the last engine request (pin OR jog) from the app's own logcat.

    MockLoc and Jogger share ONE EngineService, ONE mock provider and ONE
    MOCK_LOCATION appop — repairing the slot frees BOTH — but a re-arm must
    replay the right MODE. Two traps: a live mode switch logs
    "engine update: mode=jog @ lat, lon" (no space before the mode name), and a
    repair's own re-arm writes a fresh "pin @" line, which would otherwise make
    every later repair think the user was pinning. So: match without the leading
    space, ignore lines younger than max_age_s (our own arm), and keep the stored
    mode when nothing newer is found (it is sticky, set explicitly by --pin/--jog).
    """
    rc, out = sh("logcat -d -s PikminBotTools 2>/dev/null | grep -E '(pin|jog) @ ' | tail -5",
                 serial=serial)
    floor = cfg.get("last_mode_set", 0)
    for line in reversed([l for l in (out or "").splitlines() if l.strip()]):
        if _line_age_s(line) < max_age_s:      # emitted by our own re-arm: skip
            continue
        if _line_epoch(line) <= floor:         # older than the mode we set ourselves
            continue
        m = re.search(r"(pin|jog) @ ([-\d.]+), ([-\d.]+)", line)
        if not m:
            continue
        cfg["last_mode"] = m.group(1)
        cfg["last_lat"] = float(m.group(2))
        cfg["last_lon"] = float(m.group(3))
        cfg["persist"] = "(lifetime" not in line
        save_config(cfg)
        return cfg["last_lat"], cfg["last_lon"]
    return None


def arm_engine(serial, cfg, mode=None):
    """Re-arm the last-used mode (or the one asked for) on the shared engine."""
    pkg = cfg.get("package", PKG_DEFAULT)
    mode = mode or cfg.get("last_mode", "pin")
    lat, lon = cfg.get("last_lat"), cfg.get("last_lon")
    if lat is None or lon is None:
        return False, "no coordinates saved yet (use Pin / jog setup or --pin LAT LON)"
    cfg["last_mode"] = mode
    cfg["last_mode_set"] = time.time()
    save_config(cfg)
    if mode == "jog":
        cmd = (f"am start-foreground-service -n {pkg}/.EngineService --es mode jog "
               f"--es jogmode {cfg.get('jog_mode', 'loop')} "
               f"--ef lat {lat} --ef lon {lon} "
               f"--ef speed_kph {cfg.get('jog_speed', 10.0)} "
               f"--ef steps_per_sec {cfg.get('jog_steps', 2.0)} "
               f"--ei radius_m {int(cfg.get('jog_radius', 100))} "
               f"--ei heading {int(cfg.get('jog_heading', 0))} "
               f"--ei duration_min {int(cfg.get('jog_duration', 30))}")
    else:
        persistent = " --ez persistent true" if cfg.get("persist", True) else ""
        cmd = (f"am start-foreground-service -n {pkg}/.EngineService --es mode pin "
               f"--ef lat {lat} --ef lon {lon}{persistent}")
    rc, out = sh(cmd, serial=serial)
    return rc == 0, f"{mode} @ {lat}, {lon}"


# ------------------------------------------------------------------------ gui
def build_gui(cfg):
    import tkinter as tk
    from tkinter import ttk, messagebox

    OK, WARN, BAD, MUT = "#2e7d32", "#ef6c00", "#c62828", "#555555"

    root = tk.Tk()
    root.title(f"{APP} v{VERSION}")
    root.geometry("720x640")
    root.minsize(640, 560)

    q = queue.Queue()
    state = {"serial": None, "busy": False, "auto": False, "st": None}

    style = ttk.Style()
    try:
        style.theme_use("clam")
    except Exception:
        pass
    style.configure("Big.TButton", font=("Sans", 15, "bold"), padding=14)
    style.configure("TButton", padding=7)
    style.configure("Head.TLabel", font=("Sans", 16, "bold"))
    style.configure("Row.TLabel", font=("Monospace", 10))

    outer = ttk.Frame(root, padding=14)
    outer.pack(fill="both", expand=True)

    head = ttk.Frame(outer)
    head.pack(fill="x")
    ttk.Label(head, text="PikminBot Repair", style="Head.TLabel").pack(side="left")
    dev = ttk.Label(head, text="not connected", foreground=MUT)
    dev.pack(side="right")

    card = ttk.LabelFrame(outer, text="Mock location health", padding=10)
    card.pack(fill="x", pady=(10, 8))
    rows = {}
    for key, label in (("link", "ADB link"), ("dev", "Developer options"),
                       ("appop", "MOCK_LOCATION appop"), ("sel", "Selected app"),
                       ("eng", "Engine"), ("mock", "Mock fix live"),
                       ("ver", "App version")):
        r = ttk.Frame(card)
        r.pack(fill="x", pady=1)
        dot = tk.Label(r, text="\u25cf", fg=MUT, font=("Sans", 12))
        dot.pack(side="left")
        ttk.Label(r, text=label, width=22).pack(side="left")
        val = ttk.Label(r, text="-", style="Row.TLabel")
        val.pack(side="left")
        rows[key] = (dot, val)

    btns = ttk.Frame(outer)
    btns.pack(fill="x", pady=(2, 8))
    repair_btn = ttk.Button(btns, text="REPAIR NOW  (fix mock location)   [F5]", style="Big.TButton")
    repair_btn.pack(fill="x")

    row2 = ttk.Frame(outer)
    row2.pack(fill="x")
    connect_btn = ttk.Button(row2, text="Connect  [F6]")
    rearm_btn = ttk.Button(row2, text="Re-arm last  [F7]")
    stop_btn = ttk.Button(row2, text="Stop engine  [F8]")
    pin_btn = ttk.Button(row2, text="Pin / jog setup")
    pair_btn = ttk.Button(row2, text="Pair device")
    copy_btn = ttk.Button(row2, text="Copy status")
    for i, b in enumerate((connect_btn, rearm_btn, stop_btn, pin_btn, pair_btn, copy_btn)):
        b.grid(row=0, column=i, sticky="ew", padx=2, pady=3)
        row2.columnconfigure(i, weight=1)

    opts = ttk.Frame(outer)
    opts.pack(fill="x", pady=(6, 4))
    auto_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(opts, text="Auto-repair while this window is open (checks every 5 s)",
                    variable=auto_var).pack(side="left")

    ttk.Label(outer, text="Log").pack(anchor="w", pady=(6, 2))
    logwrap = ttk.Frame(outer)
    logwrap.pack(fill="both", expand=True)
    logbox = tk.Text(logwrap, height=11, wrap="word", font=("Monospace", 9),
                     background="#111111", foreground="#d8d8d8", insertbackground="#d8d8d8")
    scroll = ttk.Scrollbar(logwrap, orient="vertical", command=logbox.yview)
    logbox.configure(yscrollcommand=scroll.set, state="disabled")
    scroll.pack(side="right", fill="y")
    logbox.pack(side="left", fill="both", expand=True)

    def glog(msg):
        q.put(("log", f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"))

    def set_rows(st):
        if not st:
            return
        link_ok = st["connected"]
        rows["link"][0].config(fg=OK if link_ok else BAD)
        rows["link"][1].config(text=(f"{st['model']} · Android {st['android']} · {st['serial']}"
                                     if link_ok else (st.get("error") or "no device")))
        if not link_ok:
            for k in ("dev", "appop", "sel", "eng", "mock", "ver"):
                rows[k][0].config(fg=MUT)
                rows[k][1].config(text="-")
            return
        rows["dev"][0].config(fg=OK if st["dev_options"] else BAD)
        rows["dev"][1].config(text="on" if st["dev_options"] else "OFF (adb dies with it)")
        rows["appop"][0].config(fg=OK if st["appop"] == "allow" else BAD)
        rows["appop"][1].config(text=st["appop"])
        sel_ok = st["selected"] == st["package"]
        rows["sel"][0].config(fg=OK if sel_ok else BAD)
        rows["sel"][1].config(text=st["selected"] or "(none)")
        if st["degraded"]:
            rows["eng"][0].config(fg=BAD)
            rows["eng"][1].config(text="DEGRADED (slot lost, engine holding)")
        else:
            rows["eng"][0].config(fg=OK if st["engine"] else MUT)
            rows["eng"][1].config(text="running" if st["engine"] else "idle")
        rows["mock"][0].config(fg=OK if st["mock_live"] else MUT)
        rows["mock"][1].config(text=(st["fix"] or "no provider"))
        rows["ver"][0].config(fg=OK if st.get("version") else MUT)
        rows["ver"][1].config(text=st.get("version") or "-")

    def worker(fn, label):
        if state["busy"]:
            glog(f"(busy — skipped: {label})")
            return
        state["busy"] = True

        def go():
            try:
                fn()
            except Exception as e:
                glog(f"{label} failed: {e}")
            finally:
                state["busy"] = False
                q.put(("refresh", None))
        threading.Thread(target=go, daemon=True).start()

    def do_connect():
        def fn():
            glog("looking for the phone (cached endpoint → mDNS → LAN port scan)\u2026")
            s = discover(cfg, progress=glog, serial_hint=state["serial"])
            if not s:
                glog("no device found. On the phone: Settings → Developer options → "
                     "Wireless debugging ON (keep the screen on). If this PC was never "
                     "paired, use 'Pair new device'.")
                return
            state["serial"] = s
            ip = s.split(":")[0]
            if re.match(r"\d+\.\d+\.\d+\.\d+$", ip):
                cfg["phone_ip"] = ip
            cfg["serial"] = s
            save_config(cfg)
            glog(f"connected: {s}")
            remember_pin(cfg, s)
        worker(fn, "connect")

    def do_repair():
        def fn():
            s = state["serial"] or discover(cfg, progress=glog)
            if not s:
                glog("cannot repair: no device connected")
                return
            state["serial"] = s
            remember_pin(cfg, s)
            glog("repairing mock location slot\u2026")
            r = repair(s, cfg, rearm=True, emit=glog)
            if r["ok"]:
                glog("REPAIRED — slot healthy, engine re-armed"
                     + (f" at {cfg.get('last_lat')}, {cfg.get('last_lon')}"
                        if cfg.get("last_lat") is not None else ""))
                q.put(("notify", "PikminBot mock location repaired"))
            else:
                bad = [x for x in r["steps"] if not x["ok"]]
                glog(f"repair incomplete: {bad[-1]['step'] if bad else '?'} — "
                     "check wireless debugging is ON and the phone is awake")
                q.put(("notify", "PikminBot repair failed — see log"))
        worker(fn, "repair")

    def do_rearm(mode=None):
        def fn():
            s = state["serial"] or discover(cfg)
            if not s:
                glog("not connected")
                return
            remember_pin(cfg, s)
            ok, detail = arm_engine(s, cfg, mode=mode)
            glog(f"engine re-armed: {detail}" if ok else detail)
        worker(fn, "re-arm")

    def do_stop():
        def fn():
            s = state["serial"] or discover(cfg)
            if not s:
                glog("not connected")
                return
            r = stop_engine(s, cfg)
            glog(f"engine stopped (mock providers left: {r['provider_left']})")
        worker(fn, "stop")

    def do_pin_dialog():
        dlg = tk.Toplevel(root)
        dlg.title("Pin / jog setup")
        dlg.transient(root)
        dlg.grab_set()
        f = ttk.Frame(dlg, padding=12)
        f.pack(fill="both", expand=True)
        ttk.Label(f, text="Latitude").grid(row=0, column=0, sticky="w")
        lat = ttk.Entry(f, width=16)
        lat.grid(row=0, column=1, padx=6, pady=3)
        lat.insert(0, str(cfg.get("last_lat", 22.3193)))
        ttk.Label(f, text="Longitude").grid(row=1, column=0, sticky="w")
        lon = ttk.Entry(f, width=16)
        lon.grid(row=1, column=1, padx=6, pady=3)
        lon.insert(0, str(cfg.get("last_lon", 114.1694)))
        persist = tk.BooleanVar(value=cfg.get("persist", True))
        ttk.Checkbutton(f, text="Hold (persistent pin, else 90 s natural)",
                        variable=persist).grid(row=2, column=0, columnspan=2, sticky="w", pady=4)

        # Jogger settings — same engine, same appop: one repair frees both.
        jf = ttk.LabelFrame(f, text="Jogger", padding=8)
        jf.grid(row=4, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        jvals = {}
        for i, (key, label, dflt) in enumerate((
                ("jog_speed", "Speed km/h", 10.0), ("jog_steps", "Steps/s", 2.0),
                ("jog_radius", "Loop radius m", 100), ("jog_heading", "Heading deg", 0),
                ("jog_duration", "Duration min", 30))):
            ttk.Label(jf, text=label).grid(row=i, column=0, sticky="w")
            e = ttk.Entry(jf, width=10)
            e.grid(row=i, column=1, padx=6, pady=1, sticky="w")
            e.insert(0, str(cfg.get(key, dflt)))
            jvals[key] = e

        def ok():
            try:
                cfg["last_lat"] = float(lat.get())
                cfg["last_lon"] = float(lon.get())
            except ValueError:
                messagebox.showerror(APP, "Coordinates must be numbers")
                return
            cfg["persist"] = bool(persist.get())
            for k, e in jvals.items():
                try:
                    cfg[k] = float(e.get())
                except ValueError:
                    pass
            save_config(cfg)
            glog(f"saved coords {cfg['last_lat']}, {cfg['last_lon']} "
                 f"({'hold' if cfg['persist'] else 'natural 90 s'}) · jog "
                 f"{cfg.get('jog_speed')} km/h {cfg.get('jog_steps')} steps/s")
            dlg.destroy()
            do_rearm(mode="pin")

        def jog_now():
            ok()
            s2 = state["serial"] or discover(cfg)
            if not s2:
                glog("not connected")
                return
            good, detail = arm_engine(s2, cfg, mode="jog")
            glog(f"jog: {detail}" if good else detail)

        ttk.Button(f, text="Save + pin", command=ok).grid(
            row=3, column=0, pady=(6, 0), sticky="ew")
        ttk.Button(f, text="Save + start jog", command=jog_now).grid(
            row=3, column=1, pady=(6, 0), sticky="ew", padx=(6, 0))

    def do_pair_dialog():
        dlg = tk.Toplevel(root)
        dlg.title("Pair a new device")
        dlg.transient(root)
        dlg.grab_set()
        f = ttk.Frame(dlg, padding=12)
        f.pack(fill="both", expand=True)
        ttk.Label(f, text="On the phone: Developer options → Wireless debugging →\n"
                          "\u201cPair device with pairing code\u201d", justify="left").grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 8))
        ttk.Label(f, text="IP:PORT from the dialog").grid(row=1, column=0, sticky="w")
        ep = ttk.Entry(f, width=26)
        ep.grid(row=1, column=1, padx=6, pady=3)
        ep.insert(0, (cfg.get("phone_ip") or "192.168.0.1") + ":")
        ttk.Label(f, text="6-digit code").grid(row=2, column=0, sticky="w")
        code = ttk.Entry(f, width=12)
        code.grid(row=2, column=1, padx=6, pady=3, sticky="w")

        def ok():
            e, c = ep.get().strip(), code.get().strip()
            if ":" not in e or not c:
                messagebox.showerror(APP, "Enter IP:PORT and the pairing code")
                return
            dlg.destroy()

            def fn():
                glog(f"pairing {e}\u2026")
                good, out = pair(cfg, e, c)
                glog(out or ("paired" if good else "pairing failed"))
                if good:
                    s = discover(cfg, progress=glog)
                    state["serial"] = s
                    glog(f"connected: {s}")
            worker(fn, "pair")
        ttk.Button(f, text="Pair", command=ok).grid(row=3, column=0, columnspan=2, pady=(8, 0), sticky="ew")

    def do_copy():
        st = state.get("st")
        if not st:
            glog("nothing to copy yet")
            return
        txt = json.dumps(st, indent=2)
        root.clipboard_clear()
        root.clipboard_append(txt)
        glog("status JSON copied to clipboard")
        q.put(("notify", "PikminBot status copied"))

    def auto_loop():
        if auto_var.get():
            def fn():
                s = state["serial"] or discover(cfg)
                if s:
                    state["serial"] = s
                    remember_pin(cfg, s)
                    if not status(s, cfg)["healthy"]:
                        glog("auto-repair: slot unhealthy, repairing")
                        r = repair(s, cfg, rearm=True)
                        if r["ok"]:
                            q.put(("notify", "PikminBot auto-repaired"))
            worker(fn, "auto-repair")
        root.after(5000, auto_loop)

    def refresh():
        def fn():
            s = state["serial"] or live_serial()
            if not s:
                q.put(("rows", None))
                q.put(("dev", "not connected"))
                return
            state["serial"] = s
            st = status(s, cfg)
            state["st"] = st
            q.put(("rows", st))
            q.put(("dev", f"{st['model']} · Android {st['android']}" if st["connected"]
                   else (st["error"] or "not connected")))
        threading.Thread(target=fn, daemon=True).start()

    def pump():
        try:
            while True:
                kind, payload = q.get_nowait()
                if kind == "log":
                    logbox.configure(state="normal")
                    logbox.insert("end", payload + "\n")
                    logbox.see("end")
                    logbox.configure(state="disabled")
                elif kind == "rows":
                    set_rows(payload)
                elif kind == "dev":
                    dev.config(text=payload)
                elif kind == "notify":
                    try:
                        subprocess.Popen(["notify-send", APP, payload])
                    except Exception:
                        pass
                elif kind == "refresh":
                    refresh()
        except queue.Empty:
            pass
        root.after(250, pump)

    repair_btn.config(command=do_repair)
    connect_btn.config(command=do_connect)
    rearm_btn.config(command=do_rearm)
    stop_btn.config(command=do_stop)
    pin_btn.config(command=do_pin_dialog)
    pair_btn.config(command=do_pair_dialog)
    copy_btn.config(command=do_copy)

    # Keyboard shortcuts: F5 is the one-click repair, and they make the app
    # drivable from a script (xdotool key F5) for end-to-end testing.
    root.bind("<F5>", lambda e: do_repair())
    root.bind("<F6>", lambda e: do_connect())
    root.bind("<F7>", lambda e: do_rearm())
    root.bind("<F8>", lambda e: do_stop())
    root.bind("<Control-r>", lambda e: do_repair())
    root.bind("<Control-q>", lambda e: root.destroy())

    glog(f"{APP} v{VERSION} · adb: {ADB or 'NOT FOUND'}")
    glog("click REPAIR NOW (or press F5) to restore the mock location slot — no phone taps needed")
    glog("shortcuts: F5 repair · F6 connect · F7 re-arm pin · F8 stop engine · Ctrl+Q quit")
    def refresh_loop():
        refresh()
        root.after(4000, refresh_loop)

    root.after(200, pump)
    root.after(300, do_connect)
    root.after(4000, refresh_loop)     # self-rescheduling: status stays live
    root.after(5200, auto_loop)
    root.mainloop()


# ----------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=APP)
    ap.add_argument("--status", action="store_true", help="print status and exit")
    ap.add_argument("--repair", action="store_true", help="repair the mock slot and exit")
    ap.add_argument("--connect", action="store_true", help="connect only")
    ap.add_argument("--discover", action="store_true", help="discovery only")
    ap.add_argument("--stop", action="store_true", help="stop the engine")
    ap.add_argument("--pair", nargs=2, metavar=("IP:PORT", "CODE"))
    ap.add_argument("--arm", action="store_true", help="re-arm the last saved pin/jog")
    ap.add_argument("--jog", action="store_true", help="start a jog (same engine + slot as MockLoc)")
    ap.add_argument("--speed", type=float, default=None, help="jog speed km/h")
    ap.add_argument("--steps", type=float, default=None, help="jog steps per second")
    ap.add_argument("--radius", type=int, default=None, help="jog loop radius m")
    ap.add_argument("--heading", type=int, default=None, help="jog heading degrees")
    ap.add_argument("--duration", type=int, default=None, help="jog duration minutes")
    ap.add_argument("--pin", nargs=2, metavar=("LAT", "LON"),
                    help="save these coordinates and pin there")
    ap.add_argument("--json", action="store_true", help="machine readable output")
    ap.add_argument("--package", default=None)
    args = ap.parse_args()

    cfg = load_config()
    if args.package:
        cfg["package"] = args.package
    cfg.setdefault("package", PKG_DEFAULT)

    if not ADB:
        (print if args.json else log)("adb not found — install platform-tools or set ADB_BIN")
        return 2

    if args.pair:
        ok, out = pair(cfg, args.pair[0], args.pair[1])
        print(out)
        return 0 if ok else 1

    if args.discover or args.connect or args.repair or args.status or args.stop \
            or args.arm or args.pin or args.jog:
        s = live_serial() or discover(cfg, progress=(None if args.json else log))
        if not s:
            print(json.dumps({"ok": False, "error": "no device found"}) if args.json
                  else "no device found (wireless debugging on? PC paired?)")
            return 1
        cfg["serial"] = s
        ip = s.split(":")[0]
        if re.match(r"^\d+\.\d+\.\d+\.\d+$", ip):
            cfg["phone_ip"] = ip
        save_config(cfg)

        if args.connect or args.discover:
            print(json.dumps({"ok": True, "serial": s}) if args.json else f"connected: {s}")
            return 0
        if args.pin:
            try:
                cfg["last_lat"], cfg["last_lon"] = float(args.pin[0]), float(args.pin[1])
            except ValueError:
                print("--pin needs numeric LAT LON")
                return 2
            cfg["last_mode"] = "pin"
            cfg["last_mode_set"] = time.time()
            save_config(cfg)
            pkg = cfg["package"]
            sh(f"am start-foreground-service -n {pkg}/.EngineService --es mode pin "
               f"--ef lat {cfg['last_lat']} --ef lon {cfg['last_lon']}"
               + (" --ez persistent true" if cfg.get("persist", True) else ""), serial=s)
            r = status(s, cfg)
            print(json.dumps({"ok": r["mock_live"], "pin": [cfg["last_lat"], cfg["last_lon"]],
                              "mock_fix": r["fix"]}) if args.json
                  else f"pinned {cfg['last_lat']}, {cfg['last_lon']} · mock fix: {r['fix']}")
            return 0 if r["mock_live"] else 1
        if args.jog:
            for k, v in (("jog_speed", args.speed), ("jog_steps", args.steps),
                         ("jog_radius", args.radius), ("jog_heading", args.heading),
                         ("jog_duration", args.duration)):
                if v is not None:
                    cfg[k] = v
            cfg["last_mode"] = "jog"
            save_config(cfg)
            ok, detail = arm_engine(s, cfg, mode="jog")
            time.sleep(4)
            r = status(s, cfg)
            if args.json:
                print(json.dumps({"ok": ok and r["mock_live"], "mode": "jog",
                                  "speed_kph": cfg.get("jog_speed", 10.0),
                                  "steps_per_sec": cfg.get("jog_steps", 2.0),
                                  "mock_fix": r["fix"]}, indent=2))
            else:
                print(f"{'jog started · ' if ok else detail + ' · '}mock fix: {r['fix']}")
            return 0 if (ok and r["mock_live"]) else 1
        if args.arm:
            remember_pin(cfg, s)
            ok, detail = arm_engine(s, cfg)
            time.sleep(3)
            r = status(s, cfg)
            print(json.dumps({"ok": ok and r["mock_live"], "detail": detail,
                              "mock_fix": r["fix"]}) if args.json
                  else f"re-armed: {detail} · mock fix: {r['fix']}")
            return 0 if (ok and r["mock_live"]) else 1
        if args.stop:
            r = stop_engine(s, cfg)
            print(json.dumps(r) if args.json else f"engine stopped (providers left: {r['provider_left']})")
            return 0
        if args.repair:
            remember_pin(cfg, s)
            r = repair(s, cfg, rearm=True)
            if args.json:
                print(json.dumps(r, indent=2))
            else:
                for x in r["steps"]:
                    log(f"{'ok  ' if x['ok'] else 'FAIL'}  {x['step']} {x['detail']}")
                log("REPAIRED" if r["ok"] else "repair incomplete")
            return 0 if r["ok"] else 1

        st = status(s, cfg)
        if args.json:
            print(json.dumps(st, indent=2))
        else:
            log(f"device      : {st['model']} · Android {st['android']} · {st['serial']}")
            log(f"app version : {st['version']}")
            log(f"dev options : {'on' if st['dev_options'] else 'OFF'}")
            log(f"appop       : {st['appop']}")
            log(f"selected    : {st['selected']}")
            log(f"engine      : {'DEGRADED (slot lost)' if st['degraded'] else ('running' if st['engine'] else 'idle')}")
            log(f"mock fix    : {st['fix'] or 'none'}")
            log(f"HEALTHY     : {st['healthy']}")
        return 0 if st["healthy"] else 1

    try:
        import tkinter  # noqa: F401
    except Exception:
        print("tkinter missing — install python3-tk", file=sys.stderr)
        return 2
    build_gui(cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
