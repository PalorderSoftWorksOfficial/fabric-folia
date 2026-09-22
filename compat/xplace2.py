import importlib.util
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)
spec2 = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(pb)


def rcon_cmd(cmd):
    r = va.Rcon(25902, timeout=8)
    try:
        return str(r.cmd(cmd)[0])
    finally:
        try:
            r.s.close()
        except OSError:
            pass


NAME = "XbotH"
bot = pb.Bot(name=NAME)
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
px, py, pz = bot.pos
print("[P] spawn", bot.pos, flush=True)
rcon_cmd(f"gamemode creative {NAME}")
rcon_cmd(f"item replace entity {NAME} weapon.mainhand with minecraft:stone 64")

# deterministic hit target: explicitly-set stone beside the bot, at feet level
hb = (int(px) + 2, int(py), int(pz))
rcon_cmd(f"setblock {hb[0]} {hb[1]} {hb[2]} minecraft:stone")
r = rcon_cmd(f"execute if block {hb[0]} {hb[1]} {hb[2]} minecraft:stone")
print(f"[P] hit block set solid at {hb}: {r[:30]}", flush=True)

# face UP => fill at (hb.x, hb.y+1, hb.z)
bot.place_block(hb[0], hb[1], hb[2])
bot.play_loop(1.5)
r = rcon_cmd(f"execute if block {hb[0]} {hb[1] + 1} {hb[2]} minecraft:stone")
print(f"[P] fill check {hb[0]},{hb[1]+1},{hb[2]}: {r[:30]} (Test passed == PLACED)", flush=True)

# now break the placed block
bot.break_block(hb[0], hb[1] + 1, hb[2])
bot.play_loop(1.5)
r = rcon_cmd(f"execute if block {hb[0]} {hb[1] + 1} {hb[2]} minecraft:stone")
print(f"[P] after break: {r[:30]} (Test failed == broken)", flush=True)
bot.sock.close()
print("[P] done", flush=True)
