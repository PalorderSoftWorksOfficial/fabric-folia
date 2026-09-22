import importlib.util
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)
spec2 = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(pb)

rcon = va.Rcon(25902)
bot = pb.Bot(name="Xbot7")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
sx, sy, sz = bot.pos
sxi, szi = int(sx), int(sz)
print("[V] spawn", bot.pos, flush=True)
for i in range(0, 70):
    rcon.cmd(f"setblock {sxi} 75 {szi + i} minecraft:stone")
    rcon.cmd(f"setblock {sxi} 76 {szi + i} minecraft:air")
    rcon.cmd(f"setblock {sxi} 77 {szi + i} minecraft:air")
time.sleep(1)
bot.send_move((float(sxi) + 0.5, 76.0, bot.pos[2]), bot.rot)
bot.play_loop(1)
tp0 = getattr(bot, "_tp_count", 0)
t0 = time.time()
while bot.pos[2] < szi + 60 and time.time() - t0 < 45:
    bot.sock.settimeout(0.12)
    try:
        bot.handle_play(bot.recv_packet())
    except Exception as e:
        print("[V] walk err:", type(e).__name__, e, flush=True)
        break
    p = bot.pos
    bot.send_move((p[0], p[1], p[2] + 0.22), bot.rot)
print("[V] walked to z", round(bot.pos[2], 1), "tps", getattr(bot, "_tp_count", 0) - tp0, flush=True)
print("[V] entity pos pre-death:", rcon.cmd("data get entity Xbot7 Pos")[0][:90], flush=True)
bot.kill_self()
bot.play_loop(3)
bot.respawn()
bot.play_loop(5)
print("[V] respawned:", bot.respawned, "client pos", bot.pos, flush=True)
print("[V] entity pos post-respawn:", rcon.cmd("data get entity Xbot7 Pos")[0][:90], flush=True)
bot.sock.close()
print("[V] done", flush=True)
