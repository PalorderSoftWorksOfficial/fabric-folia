import importlib.util
import time

spec = importlib.util.spec_from_file_location("va", "compat/validate.py")
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)
spec2 = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(pb)

rcon = va.Rcon(25902)
bot = pb.Bot(name="Xbot9")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
sx, sy, sz = bot.pos
sxi, szi = int(sx), int(sz)
print("[C] spawn", bot.pos, flush=True)
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
print("[C] walked to z", round(bot.pos[2], 1), flush=True)

captured = []
orig = bot.handle_play


def spy(pkt):
    pid, pos = pb.read_varint(pkt, 0)
    captured.append((pid, len(pkt) - pos, pkt[pos:pos + 20].hex()))
    orig(pkt)


bot.handle_play = spy
captured.clear()
bot.kill_self()
bot.play_loop(3)
print("[C] kill window pids:", [(hex(p), l) for p, l, _ in captured], flush=True)
captured.clear()
bot.respawn()
bot.play_loop(6)
print("[C] respawn window pids:", [(hex(p), l, h[:24]) for p, l, h in captured], flush=True)
print("[C] client pos", bot.pos, flush=True)
print("[C] server pos:", rcon.cmd("data get entity Xbot9 Pos")[0][:90], flush=True)
bot.sock.close()
