#!/usr/bin/env python
"""Pin uncertain serverbound IDs: one fresh connection per candidate.
Success = connection survives the probe packet (no server decode error).
Throwaway."""
import sys
import importlib.util

spec = importlib.util.spec_from_file_location("probe_bot", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pb)

HIT = pb._pos(7, 66, 70) + pb.varint(1) + pb._f32(0.5) + pb._f32(1.0) + pb._f32(0.5) \
      + pb.varint(0) + pb.varint(999) + pb._bool(False) + pb._bool(False)  # use_item_on body
ACT = pb.varint(0) + pb._pos(7, 66, 70) + pb._u8(1) + pb.varint(999)   # player_action START body


def run(tag, send_fn):
    bot = pb.Bot(name="ArbBot")
    try:
        bot.handshake()
        bot.login()
        bot.configure()
        bot.play_loop(2.5)
        if bot.pos is None:
            print(f"[{tag}] FAIL: no teleport handled before probe")
            return
        send_fn(bot)
        bot.play_loop(2.5)
        print(f"[{tag}] OK (pos={bot.pos})")
    except Exception as e:
        print(f"[{tag}] FAIL: {type(e).__name__}: {e}")
    finally:
        try:
            bot.sock.close()
        except OSError:
            pass


cases = []
for pid in (0x2B, 0x2C, 0x2D, 0x2E, 0x2F):
    cases.append((f"player_loaded@{pid:#x}", lambda b, p=pid: b.send(p)))
for pid in (0x40, 0x41, 0x42, 0x43):
    cases.append((f"use_item_on@{pid:#x}", lambda b, p=pid: b.send(p, HIT)))
for pid in (0x28, 0x29, 0x2A):
    cases.append((f"player_action@{pid:#x}", lambda b, p=pid: b.send(p, ACT)))

for tag, fn in cases:
    run(tag, fn)
print("[arb] done — cross-check fabric/run/logs/latest.log for DecoderException names")
