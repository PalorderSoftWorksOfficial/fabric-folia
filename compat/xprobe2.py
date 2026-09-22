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

bot = pb.Bot(name="Xbot2")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
sx, sy, sz = bot.pos
sx = float(int(sx)) + 0.5
sz = float(int(sz)) + 0.5
print("[X] spawned", bot.pos, flush=True)

for i in range(0, 70):
    for y in (75, 76, 77):
        rcon.cmd(f"setblock {int(sx)} {y} {int(sz) + i} minecraft:air")
time.sleep(1)
bot.send_move((sx, 75.0, bot.pos[2]), bot.rot)
bot.play_loop(1)

# walk to boundary
tp0 = getattr(bot, "_tp_count", 0)
target_z = max(sz + 60, 134.0)
t0 = time.time()
last_poll = 0.0
while time.time() - t0 < 90:
    bot.sock.settimeout(0.12)
    try:
        pkt = bot.recv_packet()
        bot.handle_play(pkt)
    except Exception as e:
        print("[X] walk recv err:", type(e).__name__, e, flush=True)
        break
    p = bot.pos
    bot.send_move((p[0], p[1], p[2] + 0.22), bot.rot)
    now = time.time()
    if now - last_poll >= 3:
        last_poll = now
        try:
            out = rcon.cmd("folia regions")[0]
            cx, cz = int(p[0]) // 16, int(p[2]) // 16
            m = re.search(rf"minecraft:overworld:{cx}\b[^\n]*\n[^\n]*", str(out))
            print(f"[X] t={now - t0:5.1f}s z={p[2]:7.2f} chunk=({cx},{cz}) "
                  f"{m.group(0)[:110] if m else 'not listed'}", flush=True)
        except Exception as e:
            print("[X] rcon poll failed:", type(e).__name__, e, flush=True)
    if p[2] >= target_z:
        print("[X] crossed boundary (target reached)", flush=True)
        break

print("[X] walk: dist", round(bot.pos[2] - sz, 2), "tps", getattr(bot, "_tp_count", 0) - tp0, flush=True)

# place/break at boundary with wire-level verification
bx, by, bz = int(bot.pos[0]) + 1, 76, int(bot.pos[2])
print("[X] place/break at", bx, by, bz, flush=True)
tp0 = getattr(bot, "_tp_count", 0)
bot.place_block(bx, by, bz)
seen_update = {"y": False}

orig = bot.handle_play


def spy(pkt):
    pid, pos = pb.read_varint(pkt, 0)
    if pid == pb.GAME_CB["BLOCK_UPDATE"]:
        seen_update["y"] = True
    orig(pkt)


bot.handle_play = spy
bot.play_loop(1.5)
print("[X] placed:", bot.blocks_placed, "block-update seen:", seen_update["y"], flush=True)
bot.break_block(bx, by, bz)
bot.play_loop(1.5)
print("[X] broke:", bot.blocks_broken, flush=True)

# kill + respawn
bot.kill_self()
bot.play_loop(3)
bot.respawn()
bot.play_loop(5)
print("[X] respawned:", bot.respawned, "pos", bot.pos, flush=True)

try:
    out = str(rcon.cmd("folia regions")[0])
    print("[X] final overworld region rows:")
    for line in out.splitlines():
        if "overworld" in line:
            print("   ", line.strip()[:130])
except Exception as e:
    print("[X] final rcon failed:", e, flush=True)

bot.sock.close()
print("[X] done")
