import importlib.util
import time

spec = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pb)

bot = pb.Bot(name="Wbot")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
start = bot.pos
print("[W] spawned", start, flush=True)

# accumulating walk: +0.22/step every ~0.12s, print status each ~2s
target_z = start[2] + 64
t0 = time.time()
last_print = t0
tp_start = getattr(bot, "_tp_count", 0)
while time.time() - t0 < 75:
    bot.sock.settimeout(0.12)
    try:
        pkt = bot.recv_packet()
        bot.handle_play(pkt)
    except Exception:
        pass
    p = bot.pos
    bot.send_move((p[0], p[1], p[2] + 0.22), bot.rot)
    now = time.time()
    if now - last_print >= 2:
        last_print = now
        print(f"[W] t={now - t0:5.1f}s dist={bot.pos[2] - start[2]:7.2f} "
              f"pos={tuple(round(v, 2) for v in bot.pos)} tp={getattr(bot, '_tp_count', 0) - tp_start}",
              flush=True)
    if bot.pos[2] >= target_z:
        print("[W] reached target", flush=True)
        break

print("[W] final", bot.pos, "dist", bot.pos[2] - start[2], "tps", getattr(bot, "_tp_count", 0) - tp_start, flush=True)
bot.sock.close()
