import importlib.util
import socket
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


NAME = "XbotD"
bot = pb.Bot(name=NAME)
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
print("[L] spawn", bot.pos, flush=True)

# place/break at boundary with correct verification coordinate
bx, by, bz = int(bot.pos[0]) + 1, 76, int(bot.pos[2]) + 1
bot.place_block(bx, by, bz)
bot.play_loop(1.5)
print(f"[L] place  -> placed at {bx},{by + 1},{bz}:",
      rcon_cmd(f"execute if block {bx} {by + 1} {bz} minecraft:stone")[:30], flush=True)
bot.break_block(bx, by, bz)
bot.play_loop(1.5)
print(f"[L] break  -> stone gone at {bx},{by + 1},{bz}:",
      rcon_cmd(f"execute if block {bx} {by + 1} {bz} minecraft:stone")[:30], flush=True)

# careless probe 1: teleport, never ack it, then move — expect resync loop, not crash
bot.send(GAME_CB := None) if False else None
bot.respawn() if False else None
print("[L] probe1: sending move without acking pending teleports for 5s...", flush=True)
t0 = time.time()
n_tp = 0
orig = bot.handle_play


def counter(pkt):
    global n_tp
    pid, pos = pb.read_varint(pkt, 0)
    if pid == pb.GAME_CB["PLAYER_POSITION_WIRE"]:
        n_tp += 1
        return  # swallow: no ack
    orig(pkt)


bot.handle_play = counter
while time.time() - t0 < 5:
    bot.sock.settimeout(0.5)
    try:
        bot.handle_play(bot.recv_packet())
    except Exception:
        break
    p = bot.pos
    bot.send_move((p[0] + 0.1, p[1], p[2]), bot.rot)
bot.handle_play = orig
print(f"[L] probe1: teleports received while unacked: {n_tp} (resync loop expected, no crash)",
      flush=True)
bot.sock.close()
time.sleep(1)

# careless probe 2: garbage packet mid-play
bot2 = pb.Bot(name="XbotE")
bot2.handshake()
bot2.login()
bot2.configure()
bot2.play_loop(3)
print("[L] probe2: sending garbage play packet (pid 0xFF, junk body)", flush=True)
try:
    bot2.send(0x7F, b"\xde\xad\xbe\xef" * 8)
    bot2.play_loop(2)
    print("[L] probe2: still connected? sending one more", flush=True)
    bot2.send(0x1F, b"\x00" * 4)
    bot2.play_loop(2)
except Exception as e:
    print("[L] probe2: connection refused/closed as expected:", type(e).__name__, flush=True)
try:
    bot2.sock.close()
except OSError:
    pass

# careless probe 3: abort mid-configuration
bot3 = pb.Bot(name="XbotF")
bot3.handshake()
bot3.login()
print("[L] probe3: aborting mid-configuration (hard close)", flush=True)
bot3.sock.close()
time.sleep(1)

# probe 4: reconnect with the same name right after probe 3's abort
bot4 = pb.Bot(name="XbotF")
bot4.handshake()
bot4.login()
bot4.configure()
bot4.play_loop(3)
print("[L] probe4: same-name relogin after abort: OK, pos", bot4.pos, flush=True)
bot4.sock.close()

print("[L] server session lines:", flush=True)
import subprocess
print("[L] done", flush=True)
