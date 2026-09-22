#!/usr/bin/env python
"""Throwaway wedge watchdog: run the bot scenario while polling `folia regions`;
on any region stuck TICKING >6s, jstack the server immediately. Deleted after use."""
import subprocess
import sys
import time

sys.path.insert(0, "compat")
from validate import Rcon  # noqa: E402

PORT = 25902
JAVA_PID = None


def find_server_pid():
    out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if ":25901" in line and "LISTENING" in line:
            return line.split()[-1]
    return None


def region_states():
    r = Rcon(PORT)
    try:
        text = r.cmd("folia regions")[0]
    finally:
        r.close()
    states = {}
    for line in text.splitlines():
        if "state=" in line:
                name = " ".join(line.strip().split()[:2])
                state = line.split("state=")[1].split(",")[0]
                ticks = int(line.split("ticks=")[1].split(",")[0])
                states[name] = (state, ticks)
    return states


def main():
    global JAVA_PID
    JAVA_PID = find_server_pid()
    print(f"server pid={JAVA_PID}")
    bot = subprocess.Popen([sys.executable, "compat/probe_bot.py"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    tickings = {}
    dumps = 0
    start = time.time()
    while bot.poll() is None and time.time() - start < 240:
        time.sleep(2)
        try:
            states = region_states()
        except Exception as e:
            print(f"rcon error: {e}")
            continue
        for name, (state, ticks) in states.items():
            if state == "TICKING":
                first, first_ticks = tickings.get(name, (time.time(), ticks))
                dur = time.time() - first
                moved = ticks - first_ticks
                if dur > 6 and moved == 0:
                    dumps += 1
                    out = f"wedge_dump_{dumps}.txt"
                    subprocess.run([r"D:/jdk-25.0.4/bin/jstack.exe", str(JAVA_PID)],
                                   stdout=open(out, "w"), stderr=subprocess.DEVNULL)
                    print(f"WEDGE: {name} TICKING {dur:.0f}s ticks frozen at {ticks} -> {out}")
                tickings[name] = (first, ticks)
            else:
                tickings.pop(name, None)
        summary = " | ".join(f"{n}:{s}@{t}" for n, (s, t) in states.items())
        print(f"[{time.time()-start:5.0f}s] {summary}")
    bot.terminate()
    print(f"done, {dumps} dump(s)")


if __name__ == "__main__":
    main()
