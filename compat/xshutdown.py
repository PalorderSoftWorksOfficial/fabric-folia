import importlib.util
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)


def rcon_cmd(cmd, timeout=10):
    r = va.Rcon(25902, timeout=timeout)
    try:
        return str(r.cmd(cmd)[0])
    finally:
        try:
            r.s.close()
        except OSError:
            pass


print("[S] regions:", flush=True)
try:
    out = rcon_cmd("folia regions")
    for l in out.splitlines()[:24]:
        print("   ", l.strip()[:130], flush=True)
except Exception as e:
    print("   failed:", e, flush=True)

print("[S] metrics:", flush=True)
try:
    out = rcon_cmd("folia metrics")
    for l in out.splitlines()[:40]:
        print("   ", l.strip()[:130], flush=True)
except Exception as e:
    print("   failed:", e, flush=True)

print("[S] players online:", flush=True)
try:
    out = rcon_cmd("list")
    print("   ", out.strip()[:100], flush=True)
except Exception as e:
    print("   failed:", e, flush=True)

print("[S] issuing stop...", flush=True)
try:
    rcon_cmd("stop", timeout=8)
except Exception as e:
    print("   (stop sent; connection closing:", type(e).__name__, ")", flush=True)
print("[S] done", flush=True)
