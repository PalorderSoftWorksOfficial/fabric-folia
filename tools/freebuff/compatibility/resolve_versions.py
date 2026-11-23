#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

PROJECTS = {
    "lithium": "server logic / collision / AI / chunk ticking",
    "c2me-fabric": "async chunk IO and chunk-system threading",
    "ferrite-core": "memory layout (blockstate/model dedup)",
    "krypton": "networking stack",
    "vmp-fabric": "entity tracking and player-density paths",
    "scalablelux": "light engine",
    "carpet": "Carpet mod (dev tooling / OFEM family)",
    "carpet-tis-addition": "Carpet TIS Addition (OFEM collision optimisation)",
}

API = "https://api.modrinth.com/v2"
UA = "fabric-folia-compat-audit/1.0 (repository audit tool)"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def versions_for(slug):
    q = urllib.parse.urlencode({
        "game_versions": json.dumps(["26.2"]),
        "loaders": json.dumps(["fabric"]),
    })
    return get(f"{API}/project/{slug}/version?{q}")


def tags_ok(version):
    return ("26.2" in version.get("game_versions", [])
            and "fabric" in version.get("loaders", []))


def pick(versions, want_version):
    matching = [v for v in versions if tags_ok(v)]
    if want_version:
        for v in matching:
            if v["version_number"] == want_version:
                return v
        raise ValueError(f"pinned version {want_version} not tagged 26.2/fabric")
    if not matching:
        return None
    return max(matching, key=lambda v: v["date_published"])


def sha512_of(path):
    h = hashlib.sha512()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(version, cache):
    files = version["files"]
    primary = next((f for f in files if f.get("primary")), files[0])
    path = os.path.join(cache, primary["filename"])
    if not os.path.exists(path):
        req = urllib.request.Request(primary["url"], headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = resp.read()
        if hashlib.sha512(data).hexdigest() != primary.get("hashes", {}).get("sha512"):
            raise ValueError("download hash mismatch")
        with open(path, "wb") as out:
            out.write(data)
    actual = sha512_of(path)
    expected = primary.get("hashes", {}).get("sha512")
    if expected and actual != expected:
        raise ValueError("cached jar hash mismatch")
    return path, actual, primary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default="/tmp/fabric-folia-compat-jars")
    ap.add_argument("--out", default=None)
    ap.add_argument("--pin", action="append", default=[])
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    pins = dict(p.split("=", 1) for p in args.pin)
    manifest = []
    for slug, role in PROJECTS.items():
        try:
            versions = versions_for(slug)
            version = pick(versions, pins.get(slug))
            if version is None:
                raise ValueError("no version tagged 26.2/fabric")
            path, sha512, file = download(version, args.cache)
            manifest.append({
                "slug": slug,
                "role": role,
                "version_number": version["version_number"],
                "name": version["name"],
                "published": version["date_published"],
                "project_id": version["project_id"],
                "version_id": version["id"],
                "game_versions": version["game_versions"],
                "loaders": version["loaders"],
                "tags_verified": tags_ok(version),
                "dependencies": version.get("dependencies", []),
                "file": file["filename"],
                "url": file["url"],
                "sha512": sha512,
                "jar": path,
                "all_26_2_versions": [x["version_number"] for x in versions if tags_ok(x)],
            })
        except Exception as exc:
            manifest.append({"slug": slug, "role": role, "error": str(exc)})
    text = json.dumps({
        "scan": "minecraft-26.2",
        "minecraft": "26.2",
        "loader": "fabric",
        "note": "Modrinth metadata only; not a compatibility result",
        "mods": manifest,
    }, indent=2)
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(text + "\n")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
