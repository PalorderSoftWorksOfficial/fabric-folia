import importlib.util
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)
spec2 = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(pb)

rcon = va.Rcon(25902)


def regions():
    return str(rcon.cmd("folia regions")[0])


def ow_rows(out):
    return [l.strip()[:140] for l in out.splitlines() if "overworld #" in l]


bot = pb.Bot(name="Xbot4")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
sx, sy, sz = bot.pos
sx = float(int(sx)) + 0.5
sz = float(int(sz)) + 0.5
before = regions()
print("[X] spawned", bot.pos, flush=True)
print("[X] regions BEFORE:")
for l in ow_rows(before):
    print("   ", l, flush=True)

# floored corridor: stone floor y=75, air y=76..77
for i in range(0, 70):
    rcon.cmd(f"setblock {int(sx)} 75 {int(sz) + i} minecraft:stone")
    rcon.cmd(f"setblock {int(sx)} 76 {int(sz) + i} minecraft:air")
    rcon.cmd(f"setblock {int(sx)} 77 {int(sz) + i} minecraft:air")
time.sleep(1)
bot.send_move((sx, 76.0, bot.pos[2]), bot.rot)
bot.play_loop(1)
print("[X] on corridor at", bot.pos, flush=True)

tp0 = getattr(bot, "_tp_count", 0)
target_z = max(sz + 60, 134.0)
t0 = time.time()
while time.time() - t0 < 60:
    bot.sock.settimeout(0.12)
    try:
        bot.handle_play(bot.recv_packet())
    except Exception as e:
        print("[X] walk err:", type(e).__name__, e, flush=True)
        break
    p = bot.pos
    bot.send_move((p[0], p[1], p[2] + 0.22), bot.rot)
    if p[2] >= target_z:
        print("[X] crossed boundary", flush=True)
        break
print("[X] walk dist", round(bot.pos[2] - sz, 2), "tps", getattr(bot, "_tp_count", 0) - tp0,
      "moves", getattr(bot, "_mv_count", 0), flush=True)
time.sleep(2)

after_walk = regions()
print("[X] regions AFTER WALK (bot z=", round(bot.pos[2], 1), "):", flush=True)
for l in ow_rows(after_walk):
    print("   ", l, flush=True)

# place/break at boundary
bx, by, bz = int(bot.pos[0]) + 1, 76, int(bot.pos[2])
print("[X] place/break at", bx, by, bz, flush=True)
seen = {"update": 0}
orig = bot.handle_play


def spy(pkt):
    pid, pos = pb.read_varint(pkt, 0)
    if pid == pb.GAME_CB["BLOCK_UPDATE"]:
        seen["update"] += 1
    orig(pkt)


bot.handle_play = spy
try:
    bot.place_block(bx, by, bz)
    bot.play_loop(1.5)
    print("[X] placed:", bot.blocks_placed, "block-updates:", seen["update"], flush=True)
    seen["update"] = 0
    bot.break_block(bx, by, bz)
    bot.play_loop(1.5)
    print("[X] broke:", bot.blocks_broken, "block-updates:", seen["update"], flush=True)

    bot.kill_self()
    bot.play_loop(3)
    print("[X] dead; respawning", flush=True)
    bot.respawn()
    bot.play_loop(5)
    print("[X] respawned:", bot.respawned, "pos", bot.pos, flush=True)
except Exception as e:
    import traceback
    traceback.print_exc()

try:
    out = regions()
    print("[X] regions AFTER RESPAWN:", flush=True)
    for l in ow_rows(out):
        print("   ", l, flush=True)
except Exception as e:
    print("[X] final rcon failed:", e, flush=True)

bot.sock.close()
print("[X] done", flush=True)
