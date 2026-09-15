#!/usr/bin/env python3
"""patch_sharedlogin.py <apk>

Fixes the repackaged-clone crash in Niantic's shared-login path:

    java.lang.SecurityException: Permission Denial: opening provider
    com.nianticlabs.platform.sharedlogin.LoginContentProvider ... requires
    com.nianticlabs.platform.permission.LOGIN_PROVIDER   (FATAL EXCEPTION)

A re-signed clone can never hold the original app's signature-level
LOGIN_PROVIDER permission, so `TokenRequestManager.queryForToken()` throws
whenever it tries to import a login token from another installed Niantic app
(e.g. the ORIGINAL Pikmin Bloom) -- and the throw is not caught, which kills
the app on the first screen after "Continue playing".

Fix: wrap the ContentResolver.query() call in try/catch(Throwable) and return
"" (the same value the method already returns when no token is found).
Shared-login token import is a convenience only; normal sign-in is unaffected.

Runs baksmali/smali out of apktool.jar (com.android.tools.smali.*) and edits
only the dex that contains TokenRequestManager. Re-zipalign (-p 4) and re-sign
afterwards.
"""
import os, re, shutil, subprocess, sys, tempfile, zipfile

APKTOOL = os.path.expanduser("~/apktool.jar")
JAVA = os.path.expanduser("~/android-sdk/jdk17/bin/java")
CLASS = "com/nianticlabs/platform/sharedlogin/TokenRequestManager.smali"

BAK = ["baksmali", "smali"]
MAINS = {
    "baksmali": "com.android.tools.smali.baksmali.Main",
    "smali": "com.android.tools.smali.smali.Main",
}


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-3000:], r.stderr[-3000:])
        raise SystemExit(f"command failed: {' '.join(cmd[:3])}...")
    return r


def patch_smali(path: str) -> bool:
    src = open(path).read()
    if "sharedlogin_query_guard" in src:
        print("  already patched")
        return False
    lines = src.split("\n")
    qi = None
    for i, ln in enumerate(lines):
        if "ContentResolver;->query(Landroid/net/Uri;" in ln:
            qi = i
            break
    if qi is None:
        raise SystemExit("query call not found in queryForToken")
    # the following non-empty line must be move-result-object
    mi = qi + 1
    while not lines[mi].strip():
        mi += 1
    if "move-result-object" not in lines[mi]:
        raise SystemExit(f"unexpected instruction after query: {lines[mi]!r}")
    indent = re.match(r"(\s*)", lines[qi]).group(1)
    guard = [
        f"{indent}# sharedlogin_query_guard: a re-signed clone cannot hold the",
        f"{indent}# original app's signature-level LOGIN_PROVIDER permission, so the",
        f"{indent}# cross-app token import below throws SecurityException.",
        f"{indent}:try_start_sharedlogin",
    ]
    after = [
        f"{indent}:try_end_sharedlogin",
        f"{indent}.catch Ljava/lang/Throwable; {{:try_start_sharedlogin .. :try_end_sharedlogin}} :catch_sharedlogin",
        "",
        f"{indent}goto :after_sharedlogin",
        "",
        f"{indent}:catch_sharedlogin",
        f"{indent}move-exception p1",
        "",
        f'{indent}const-string p1, ""',
        "",
        f"{indent}return-object p1",
        "",
        f"{indent}:after_sharedlogin",
    ]
    out = lines[:qi] + guard + lines[qi:mi + 1] + after + lines[mi + 1:]
    open(path, "w").write("\n".join(out))
    print("  patched queryForToken (try/catch -> \"\")")
    return True


def main(apk):
    tmp = tempfile.mkdtemp(prefix="slpatch_")
    dexdir = os.path.join(tmp, "dex")
    os.makedirs(dexdir)
    with zipfile.ZipFile(apk) as z:
        dex_names = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        z.extractall(dexdir, dex_names)
    target = None
    for n in dex_names:
        if b"TokenRequestManager" in open(os.path.join(dexdir, n), "rb").read():
            target = n
            break
    if not target:
        raise SystemExit("TokenRequestManager not found in any dex")
    print(f"  dex: {target}")

    dec = os.path.join(tmp, "dec")
    run([JAVA, "-cp", APKTOOL, MAINS["baksmali"], "disassemble",
         "-o", dec, os.path.join(dexdir, target)])
    smali = os.path.join(dec, CLASS)
    if not os.path.exists(smali):
        raise SystemExit("TokenRequestManager.smali missing after disassembly")
    patch_smali(smali)

    newdex = os.path.join(tmp, "patched.dex")
    run([JAVA, "-cp", APKTOOL, MAINS["smali"], "assemble", "-o", newdex, dec])

    # repackage: swap the dex, keep every other entry byte-identical
    out = apk + ".sl.tmp"
    with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == target:
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zout.writestr(zi, open(newdex, "rb").read())
            else:
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zout.writestr(zi, zin.read(item.filename))
    shutil.move(out, apk)
    shutil.rmtree(tmp, ignore_errors=True)
    print(f"OK {apk}")


if __name__ == "__main__":
    main(sys.argv[1])
