#!/usr/bin/env python3
# Live verification of the server-GUI window icon (Folia upstream parity).
#
# Boots a REAL production server instance (the same assembled baseline the
# compat harness uses: Fabric launcher + mod jars, NOT the dev environment)
# WITHOUT `nogui`, on a machine with a display, then asserts the mixin fired:
#
#   1. "Server GUI icon set from logo.png (512x512)" in the server log —
#      logged by ServerGuiIcon.applyTo after setIconImage succeeded.
#   2. No GUI-scope warnings or exceptions from FabricFolia/GUI.
#   3. Graceful stop.
#
# The window icon itself is carried by the OS window manager; the decoded
# image and the setIconImage call are verified in-process by
# ServerGuiIconTest.applyToSetsRealFrameIcon on a real JFrame.
import importlib.util
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

spec = importlib.util.spec_from_file_location("validate", os.path.join(HERE, "validate.py"))
validate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validate)


def main():
    print("== assembling baseline production instance (no optimization mods) ==")
    inst_dir, launcher, port, rcon_port, resolved = validate.assemble("baseline")
    print(f"instance: {inst_dir} (rcon {rcon_port})")
    print(f"mods: {resolved}")

    log_path = os.path.join(inst_dir, "gui-icon-check.stdout.log")
    proc = subprocess.Popen(
        ["java", "-Xmx2G", "-jar", "fabric-server-launch.jar"],  # NO nogui
        cwd=inst_dir,
        stdout=open(log_path, "wb"),
        stderr=subprocess.STDOUT)
    results = []

    def phase(name, ok, detail=""):
        results.append((name, ok, detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))

    try:
        # wait for boot
        booted = False
        for _ in range(90):
            time.sleep(2)
            try:
                with open(log_path, encoding="utf-8", errors="replace") as f:
                    tail = f.read()[-8000:]
                if "Done (" in tail and "RCON running" in tail:
                    booted = True
                    break
                if "Exception in thread" in tail and "Done (" not in tail:
                    break
            except OSError:
                break
        phase("server boots with GUI enabled", booted)

        with open(log_path, encoding="utf-8", errors="replace") as f:
            log = f.read()
        icon_line = re.findall(r"Server GUI icon set from logo\.png \((\d+)x(\d+)\)", log)
        phase("icon applied to GUI frame (mixin fired)", bool(icon_line),
              f"{icon_line[0][0]}x{icon_line[0][1]}" if icon_line else "no icon line in log")
        bad = [ln for ln in log.splitlines()
               if ("Could not apply" in ln or "GUI" in ln and "Exception" in ln)]
        phase("no GUI-scope errors", not bad, f"{len(bad)} offending lines")
    finally:
        try:
            r = validate.Rcon(rcon_port, timeout=5)
            r.cmd("stop")
            r.close()
        except Exception:
            pass
        try:
            proc.wait(timeout=120)
        except subprocess.TimeoutExpired:
            proc.kill()
        stopped = re.search(
            r"Thread RCON Listener stopped|All dimensions are saved",
            open(log_path, encoding="utf-8", errors="replace").read()[-4000:]) is not None
        phase("graceful shutdown", stopped)

    ok = all(p[1] for p in results)
    print(f"== GUI icon verification: {'PASS' if ok else 'FAIL'} "
          f"({sum(1 for p in results if p[1])}/{len(results)}) ==")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
