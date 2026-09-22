import importlib.util
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)
spec2 = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(pb)

rcon = va.Rcon(25902)

bot = pb.Bot(name="Wbot")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
sx, sy, sz = bot.pos
sx = float(int(sx)) + 0.5
sz = float(int(sz)) + 0.5
print("[W] spawned", bot.pos, "-> corridor x", sx, "z", int(sz), "+64", flush=True)

for i in range(0, 70):
    for y in (75, 76, 77):
        r = rcon.cmd(f"setblock {int(sx)} {y} {int(sz) + i} minecraft:air")
print("[W] corridor cleared", flush=True)
time.sleep(1)

y_walk = 75.0
p = bot.pos
bot.send_move((sx, y_walk, p[2]), bot.rot)
bot.play_loop(1)
print("[W] re-teleported onto corridor?", bot.pos, flush=True)
# if server snapped us back, walk from where the server put us
tp0 = getattr(bot, "_tp_count", 0)

target_z = sz + 60
t0 = time.time()
last_print = t0
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
        print(f"[W] t={now - t0:5.1f}s dist={bot.pos[2] - sz:7.2f} "
              f"pos={tuple(round(v, 2) for v in bot.pos)} tp={getattr(bot, '_tp_count', 0) - tp0}",
              flush=True)
    if bot.pos[2] >= target_z:
        print("[W] reached target", flush=True)
        break

print("[W] final", bot.pos, "dist", bot.pos[2] - sz, "tps", getattr(bot, "_tp_count", 0) - tp0, flush=True)
bot.sock.close()
