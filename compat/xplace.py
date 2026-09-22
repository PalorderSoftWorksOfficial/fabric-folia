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


NAME = "XbotG"
bot = pb.Bot(name=NAME)
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
print("[P] spawn", bot.pos, flush=True)

print("[P] gamemode:", rcon_cmd(f"gamemode creative {NAME}")[:60], flush=True)
print("[P] give:", rcon_cmd(f"item replace entity {NAME} weapon.mainhand with minecraft:stone 64")[:60], flush=True)

bx, by, bz = int(bot.pos[0]) + 1, int(bot.pos[1]) + 1, int(bot.pos[2])
print(f"[P] placing via USE_ITEM_ON at face of ({bx},{by},{bz}) -> fills ({bx},{by+1},{bz})", flush=True)
bot.place_block(bx, by, bz)
bot.play_loop(1.5)
print("[P] after place, execute if block:",
      rcon_cmd(f"execute if block {bx} {by + 1} {bz} minecraft:stone")[:40], flush=True)
bot.break_block(bx, by + 1, bz)
bot.play_loop(1.5)
print("[P] after break, execute if block:",
      rcon_cmd(f"execute if block {bx} {by + 1} {bz} minecraft:stone")[:40],
      "(Test failed == air == broken)", flush=True)
bot.sock.close()
print("[P] done", flush=True)
