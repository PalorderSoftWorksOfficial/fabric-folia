import random, time
from mcrcon import MCRcon
random.seed(50)
centers = [[int(v) for v in l.split()] for l in open("centers.txt")]
PER = 2500
with MCRcon("127.0.0.1", "foliapass", port=25902) as m:
    t0=time.time(); n=0
    for pi,(cx,cz) in enumerate(centers):
        for _ in range(PER):
            m.command(f"summon minecraft:armor_stand {cx+random.randint(-24,24)} 90 {cz+random.randint(-24,24)}")
            n += 1
        print(f"patch{pi}: {n} total, {n/(time.time()-t0):.0f}/s", flush=True)
    print(f"DONE {n} in {time.time()-t0:.0f}s", flush=True)
    print("count:", m.command("execute if entity @e[type=minecraft:armor_stand]")[:80].replace("\r",""))
