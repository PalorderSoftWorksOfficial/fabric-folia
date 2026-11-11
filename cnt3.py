from mcrcon import MCRcon
with MCRcon("127.0.0.1", "foliapass", port=25902) as m:
    print("count:", m.command("execute if entity @e[type=minecraft:armor_stand]")[:80].replace("\r",""))
