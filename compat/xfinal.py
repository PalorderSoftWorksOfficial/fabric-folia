import importlib.util
import re
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)
spec2 = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(pb)

rcon = va.Rcon(25902)
NAME = "XbotC"
bot = pb.Bot(name=NAME)
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
sx, sy, sz = bot.pos
sxi, szi = int(sx), int(sz)
print("[F] spawn", bot.pos, flush=True)

print("[F] regions BEFORE:")
for l in str(rcon.cmd("folia regions")[0]).splitlines():
    if "overworld #" in l:
        print("   ", l.strip()[:120], flush=True)

for i in range(0, 70):
    rcon.cmd(f"setblock {sxi} 75 {szi + i} minecraft:stone")
    rcon.cmd(f"setblock {sxi} 76 {szi + i} minecraft:air")
    rcon.cmd(f"setblock {sxi} 77 {szi + i} minecraft:air")
time.sleep(1)
bot.send_move((float(sxi) + 0.5, 76.0, bot.pos[2]), bot.rot)
bot.play_loop(1)
t0 = time.time()
while bot.pos[2] < szi + 60 and time.time() - t0 < 45:
    bot.sock.settimeout(0.12)
    try:
        bot.handle_play(bot.recv_packet())
    except Exception:
        break
    p = bot.pos
    bot.send_move((p[0], p[1], p[2] + 0.22), bot.rot)
print("[F] walked to z", round(bot.pos[2], 1), flush=True)

# chat-command control: xp add, then read levels server-side
bot.send_chat_command(f"xp add {NAME} 5 levels")
bot.play_loop(1.5)
print("[F] xp control:", rcon.cmd(f"data get entity {NAME} XpLevel")[0][:70], flush=True)

# boundary place/break with authoritative verification
bx, by, bz = int(bot.pos[0]) + 1, 76, int(bot.pos[2])
bot.place_block(bx, by, bz)
bot.play_loop(1.5)
placed = rcon.cmd(f"execute if block {bx} {by} {bz} minecraft:stone")[0]
print(f"[F] place  at {bx},{by},{bz}: server says: {str(placed)[:60]}", flush=True)
bot.break_block(bx, by, bz)
bot.play_loop(1.5)
broken = rcon.cmd(f"execute if block {bx} {by} {bz} minecraft:stone")[0]
print(f"[F] break  at {bx},{by},{bz}: stone still there? {str(broken)[:60]}", flush=True)

# kill via console (no chat-command confound), then respawn via client command
rcon.cmd(f"kill {NAME}")
t_kill = time.time()
while time.time() - t_kill < 5:
    bot.play_loop(0.5)
    if bot.saw_death:
        break
print("[F] death seen on client:", bot.saw_death, flush=True)
bot.respawn()
t_resp = time.time()
while time.time() - t_resp < 8:
    bot.play_loop(0.5)
print("[F] client pos after respawn:", bot.pos, flush=True)
print("[F] server pos after respawn:", rcon.cmd(f"data get entity {NAME} Pos")[0][:80], flush=True)
print("[F] health after respawn:", rcon.cmd(f"data get entity {NAME} Health")[0][:60], flush=True)

print("[F] regions AFTER:")
for l in str(rcon.cmd("folia regions")[0]).splitlines():
    if "overworld #" in l:
        print("   ", l.strip()[:120], flush=True)
out = str(rcon.cmd("folia metrics")[0])
print("[F] metrics:", out[:400], flush=True)
bot.sock.close()
print("[F] done", flush=True)
