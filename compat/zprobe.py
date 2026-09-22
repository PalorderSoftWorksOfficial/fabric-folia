import importlib.util
import sys

spec = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pb)

bot = pb.Bot(name="Zbot")
bot.handshake()
bot.login()
bot.configure()
bot.play_loop(4)
print("[Z] spawned", bot.pos, flush=True)
tp0 = getattr(bot, "_tp_count", 0)


def a(b):
    b.send_move(b.pos, b.rot)


bot.play_loop(5, act=a)
print("[A] zero-delta moves: teleports:", getattr(bot, "_tp_count", 0) - tp0, flush=True)
tp0 = getattr(bot, "_tp_count", 0)

p = bot.pos
bot.send_move((p[0] + 0.1, p[1], p[2]), bot.rot)
bot.play_loop(3)
print("[B] single 0.1 step: teleports:", getattr(bot, "_tp_count", 0) - tp0, "pos now", bot.pos, flush=True)
tp0 = getattr(bot, "_tp_count", 0)


def c(b):
    q = b.pos
    b.send_move((q[0] + 0.05, q[1], q[2]), b.rot)


bot.play_loop(2, act=c)
print("[C] walk-like steps: teleports:", getattr(bot, "_tp_count", 0) - tp0, flush=True)
bot.sock.close()
print("[Z] done")
