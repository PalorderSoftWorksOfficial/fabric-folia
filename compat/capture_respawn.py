import importlib.util
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)
spec2 = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(pb)

rcon = va.Rcon(25902)
bot = pb.Bot(name="Xbot8")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
print("[C] spawn", bot.pos, flush=True)

captured = []
orig = bot.handle_play


def spy(pkt):
    pid, pos = pb.read_varint(pkt, 0)
    captured.append((pid, len(pkt) - pos, pkt[pos:pos + 16].hex()))
    orig(pkt)


bot.handle_play = spy
captured.clear()
bot.kill_self()
bot.play_loop(3)
print("[C] after kill, captured:", [(hex(p), l) for p, l, _ in captured][:20], flush=True)
captured.clear()
bot.respawn()
bot.play_loop(6)
print("[C] after respawn, captured:", [(hex(p), l, h[:24]) for p, l, h in captured][:30], flush=True)
print("[C] client pos now", bot.pos, flush=True)
print("[C] server entity pos:", rcon.cmd("data get entity Xbot8 Pos")[0][:90], flush=True)
bot.sock.close()
