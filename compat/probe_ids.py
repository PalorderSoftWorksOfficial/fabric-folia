#!/usr/bin/env python
"""Play-phase ID probe: connect, reach play, log (id, len, head) for every
inbound play packet, then quit. Read the wire, believe the wire.
Throwaway: delete after the ID table is settled."""
import sys
import importlib.util

spec = importlib.util.spec_from_file_location("probe_bot", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pb)

bot = pb.Bot(name="ProbeBot")
try:
    bot.handshake()
    bot.login()
    bot.configure()
    print("[probe] in PLAY; dumping packets")
    bot.play_loop(6)
finally:
    seen = {}
    for line in bot.log:
        if line.startswith("play pid="):
            key = line.split()[1]
            seen.setdefault(key, []).append(line)
    for key in sorted(seen, key=lambda k: int(k.split("=")[1], 16)):
        lines = seen[key]
        print(f"{key}  n={len(lines):3d}  {lines[0].split(' ', 1)[1]}")
