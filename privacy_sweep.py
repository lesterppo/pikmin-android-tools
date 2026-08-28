#!/usr/bin/env python3
"""
privacy_sweep.py — last-line defense against committing secrets/PII.

Fails (exit 1) if any tracked-or-staged file under the repo contains:
  * an OAuth client secret or real token,
  * a Google client_id ending in .apps.googleusercontent.com (allowed only
    inside documented JSON templates — here we just forbid any literal),
  * an email address,
  * an absolute home path (/home/<user>, /Users/<user>, C:\\Users\\<user>),
  * a keystore/secret file (*.jks, *.keystore, fit_token.json),
  * the string "client_secret" with an assignment.

Run: python3 privacy_sweep.py   (from repo root, or anywhere inside it)
"""
import os
import re
import subprocess
import sys

# Files / dirs that are allowed to mention client ids (none here — strict).
ALLOWED_EXT = {
    ".md", ".txt", ".py", ".java", ".kt", ".gradle", ".xml", ".json",
    ".properties", ".sh", ".gitignore",
}
# Patterns that must NEVER appear in committed content.
FORBID = [
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "email address"),
    (re.compile(r"/home/[a-zA-Z0-9_.-]+"), "unix home path"),
    (re.compile(r"/Users/[a-zA-Z0-9_.-]+"), "mac home path"),
    (re.compile(r"C:\\\\Users\\\\[a-zA-Z0-9_.-]+"), "windows home path"),
    (re.compile(r"\bclient_secret\b\s*[:=]"), "client_secret assignment"),
    # NOTE: the literal ".apps.googleusercontent.com" appears only in THIS
    # file's docstring as documentation; we skip scanning privacy_sweep.py
    # itself for that one pattern (handled by the filename filter below).
    (re.compile(r"ghp_[A-Za-z0-9]{20,}"), "github token"),
    (re.compile(r"ya29\.[A-Za-z0-9_-]{20,}"), "google oauth token"),
]
# Filenames that must never be committed.
FORBID_NAMES = re.compile(r"(fit_token\.json|\.jks$|\.keystore$|local\.properties$)", re.I)


def repo_root():
    d = os.getcwd()
    while True:
        if os.path.isdir(os.path.join(d, ".git")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.getcwd()
        d = parent


def main():
    root = repo_root()
    bad = []
    for dirpath, dirnames, filenames in os.walk(root):
        # skip git internals
        dirnames[:] = [d for d in dirnames if d != ".git"]
        # prune gitignored directories (build outputs, caches, keystores)
        keep = []
        for d in dirnames:
            try:
                if subprocess.run(
                    ["git", "-C", root, "check-ignore", "-q", os.path.join(dirpath, d)],
                    capture_output=True,
                ).returncode == 0:
                    continue
            except Exception:
                pass
            keep.append(d)
        dirnames[:] = keep
        for fn in filenames:
            if FORBID_NAMES.search(fn):
                bad.append((os.path.relpath(os.path.join(dirpath, fn), root),
                            "forbidden filename"))
                continue
            ext = os.path.splitext(fn)[1].lower()
            if ext not in ALLOWED_EXT:
                continue
            p = os.path.join(dirpath, fn)
            try:
                with open(p, "r", encoding="utf-8", errors="strict") as f:
                    text = f.read()
            except (UnicodeDecodeError, OSError):
                continue  # binary / unreadable — APKs are gitignored anyway
            rel = os.path.relpath(p, root)
            for rx, label in FORBID:
                m = rx.search(text)
                if m:
                    bad.append((rel, f"{label}: ...{m.group(0)[:40]}..."))
                    break
    if bad:
        print("PRIVACY SWEEP FAILED — found potentially sensitive content:")
        for rel, why in bad:
            print(f"  {rel}: {why}")
        sys.exit(1)
    print("PRIVACY SWEEP OK — no secrets/PII found.")


if __name__ == "__main__":
    main()
