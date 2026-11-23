#!/usr/bin/env python3
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import PurePosixPath

MAX_MEMBER_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
MAX_DEPTH = 4
MAX_CONTAINERS = 200

ANNOTATION_FQN = re.compile(
    r"(org\.spongepowered\.asm\.mixin(?:\.[A-Za-z0-9_$]+)+"
    r"|com\.llamalad7\.mixinextras(?:\.[A-Za-z0-9_$]+)+)\("
)
MEMBER_LINE = re.compile(r"^  \S.*\);\s*$", re.M)
MIXIN_TARGETS_STR = re.compile(r"targets\s*=\s*\[(.*?)\]", re.S)
MIXIN_TARGETS_CLASS = re.compile(r"class L([^;]+);")
METHOD_LIST = re.compile(r"\bmethod\s*=\s*\[(.*?)\]", re.S)
METHOD_ONE = re.compile(r'\bmethod\s*=\s*"([^"]*)"')
STRINGS = re.compile(r'"([^"]*)"')
AT_VALUE = re.compile(r'\bvalue\s*=\s*"([^"]*)"')
AT_TARGET = re.compile(r'\btarget\s*=\s*"([^"]*)"')
AT_ORDINAL = re.compile(r"\bordinal\s*=\s*(-?\d+)")
AT_SHIFT = re.compile(r'\bshift\s*=\s*[A-Za-z_.]*\.?([A-Z_]+)')
REQUIRE = re.compile(r"\brequire\s*=\s*(-?\d+)")
EXPECT = re.compile(r"\bexpect\s*=\s*(-?\d+)")
SLICE = re.compile(r'\bslice\s*=\s*"([^"]*)"')

NON_INJECTOR = {"Mixin", "At", "Slice", "Desc", "Shadow", "Final", "Unique",
                "Mutable", "Dynamic", "Pseudo", "Synchronized", "Debug",
                "Intrinsic", "Interface", "Implements"}
HANDLER_KINDS = {
    "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable",
    "ModifyConstant", "ModifyExpressionValue", "ModifyReceiver",
    "ModifyReturnValue", "WrapOperation", "WrapWithCondition", "WrapMethod",
    "Overwrite", "Accessor", "Invoker",
}
REWRITE_KINDS = {"Overwrite", "Redirect", "ModifyArg", "ModifyArgs",
                 "ModifyConstant", "WrapOperation", "WrapWithCondition",
                 "WrapMethod"}


def sha512_of(path):
    h = hashlib.sha512()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def safe_extract(jar, dest):
    root = os.path.realpath(dest)
    os.makedirs(root, exist_ok=True)
    total = 0
    with zipfile.ZipFile(jar) as zf:
        for info in zf.infolist():
            name = info.filename
            pure = PurePosixPath(name)
            if pure.is_absolute() or ".." in pure.parts:
                raise ValueError(f"path traversal in archive: {name!r}")
            if pure.parts and re.match(r"^[A-Za-z]:", pure.parts[0]):
                raise ValueError(f"absolute drive path in archive: {name!r}")
            if info.file_size > MAX_MEMBER_BYTES:
                raise ValueError(f"oversized member {name!r}")
            total += info.file_size
            if total > MAX_TOTAL_BYTES:
                raise ValueError("decompressed size cap exceeded")
        try:
            zf.extractall(root, filter="data")
        except TypeError:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                parts = PurePosixPath(info.filename).parts
                target = os.path.join(root, *parts)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with zf.open(info) as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst, 1 << 20)
    return root


def balanced(text, start):
    depth = 0
    i = start
    while i < len(text):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def annotation_blocks(text):
    out = []
    seen = set()
    for m in ANNOTATION_FQN.finditer(text):
        name = m.group(1)
        open_paren = m.end() - 1
        close = balanced(text, open_paren)
        if close < 0:
            continue
        key = (name, open_paren)
        if key in seen:
            continue
        seen.add(key)
        out.append({"name": name, "kind": name.rsplit(".", 1)[-1],
                    "args": text[open_paren + 1:close],
                    "start": m.start()})
    return out


def at_selectors(args):
    selectors = []
    for block in annotation_blocks(args):
        if block["kind"] != "At":
            continue
        value = AT_VALUE.search(block["args"])
        target = AT_TARGET.search(block["args"])
        ordinal = AT_ORDINAL.search(block["args"])
        shift = AT_SHIFT.search(block["args"])
        slice_id = SLICE.search(block["args"])
        selectors.append({
            "value": value.group(1) if value else None,
            "target": target.group(1) if target else None,
            "ordinal": int(ordinal.group(1)) if ordinal else None,
            "shift": shift.group(1) if shift else None,
            "slice": slice_id.group(1) if slice_id else None,
        })
    return selectors


def handler_methods(args):
    listed = METHOD_LIST.search(args)
    if listed:
        return STRINGS.findall(listed.group(1))
    single = METHOD_ONE.search(args)
    return [single.group(1)] if single else []


def member_name(line):
    m = re.search(r"([A-Za-z0-9_$<>]+)\s*\(", line)
    return m.group(1) if m else line.strip()


def inspect_class(container, cls):
    entry = {"class": cls, "ok": False, "targets": [], "handlers": [],
             "annotation_counts": {}, "error": None}
    try:
        proc = subprocess.run(
            ["javap", "-v", "-p", "-cp", container, cls],
            capture_output=True, text=True, timeout=120,
        )
    except Exception as exc:
        entry["error"] = str(exc)
        return entry
    if proc.returncode != 0:
        entry["error"] = (proc.stderr or proc.stdout).strip().splitlines()[-1:] or ["javap failed"]
        entry["error"] = entry["error"][0] if entry["error"] else "javap failed"
        return entry
    text = proc.stdout
    entry["ok"] = True
    members = [(m.start(), member_name(m.group(0))) for m in MEMBER_LINE.finditer(text)]
    counts = {}
    for block in annotation_blocks(text):
        kind = block["kind"]
        if kind in ("At", "Slice"):
            continue
        counts[kind] = counts.get(kind, 0) + 1
        if kind == "Mixin":
            for group in MIXIN_TARGETS_STR.findall(block["args"]):
                for value in STRINGS.findall(group):
                    entry["targets"].append(value.replace("/", "."))
            for group in MIXIN_TARGETS_CLASS.findall(block["args"]):
                entry["targets"].append(group.replace("/", "."))
            continue
        if kind not in HANDLER_KINDS:
            continue
        handler = None
        for start, name in members:
            if start < block["start"]:
                handler = name
            else:
                break
        require = REQUIRE.search(block["args"])
        expect = EXPECT.search(block["args"])
        entry["handlers"].append({
            "handler": handler,
            "kind": kind,
            "methods": handler_methods(block["args"]),
            "at": at_selectors(block["args"]),
            "require": int(require.group(1)) if require else None,
            "expect": int(expect.group(1)) if expect else None,
        })
    entry["annotation_counts"] = counts
    entry["targets"] = sorted(set(entry["targets"]))
    return entry


def load_meta(container):
    path = os.path.join(container, "fabric.mod.json")
    if not os.path.exists(path):
        return None
    with open(path) as fh:
        return json.load(fh)


def mixin_declarations(meta):
    if not meta:
        return []
    out = []
    for item in meta.get("mixins", []) or []:
        if isinstance(item, str):
            out.append({"config": item, "environment": None})
        elif isinstance(item, dict):
            out.append({"config": item.get("config"), "environment": item.get("environment")})
    return out


def find_configs(container):
    names = []
    for name in sorted(os.listdir(container)):
        if name.endswith(".mixins.json") or name.endswith(".mixin.json"):
            names.append(name)
    return names


def config_info(container, name, declared):
    path = os.path.join(container, name)
    info = {"config": name, "declared_by_fabric_mod_json": declared,
            "exists": os.path.exists(path), "environment": None}
    if not info["exists"]:
        info["error"] = "declared mixin config missing from jar"
        return info, []
    try:
        with open(path) as fh:
            cfg = json.load(fh)
    except Exception as exc:
        info["error"] = f"unreadable mixin config: {exc}"
        return info, []
    pkg = cfg.get("package", "")
    mixins = list(cfg.get("mixins", []) or [])
    server = list(cfg.get("server", []) or [])
    client = list(cfg.get("client", []) or [])
    classes = mixins + server + client
    plugin = cfg.get("plugin")
    plugin_path = plugin.replace(".", "/") + ".class" if plugin else None
    info.update({
        "package": pkg,
        "parent": cfg.get("parent"),
        "plugin": plugin,
        "plugin_present": (os.path.exists(os.path.join(container, plugin_path))
                           if plugin_path else None),
        "required": cfg.get("required"),
        "defaultRequire": (cfg.get("injectors") or {}).get("defaultRequire"),
        "compatibilityLevel": cfg.get("compatibilityLevel"),
        "refmap": cfg.get("refmap"),
        "mixins": len(mixins),
        "server": len(server),
        "client": len(client),
        "class_count": len(classes),
        "server_class_count": len(mixins) + len(server),
    })
    qualified = [f"{pkg}.{c}" if pkg else c for c in classes]
    missing = []
    for cls in qualified:
        rel = cls.replace(".", "/") + ".class"
        if not os.path.exists(os.path.join(container, rel)):
            missing.append(cls)
    info["missing_classes"] = missing
    info["client_only_classes"] = [f"{pkg}.{c}" if pkg else c for c in client]
    return info, qualified


def nested_jars(container):
    jars_dir = os.path.join(container, "META-INF", "jars")
    if not os.path.isdir(jars_dir):
        return []
    return sorted(n for n in os.listdir(jars_dir) if n.endswith(".jar"))


def sanitize(label):
    return re.sub(r"[^A-Za-z0-9._-]+", "__", label)


def inspect_container(jar, label, root, depth, chain, pool):
    rel = os.path.relpath(jar, root) if os.path.abspath(jar).startswith(os.path.abspath(root)) else os.path.basename(jar)
    module = {
        "label": label,
        "jar": os.path.basename(jar),
        "jar_relpath": rel,
        "depth": depth,
        "sha512": sha512_of(jar),
        "size": os.path.getsize(jar),
        "fabric_mod_json": False,
        "mixin_declarations": [],
        "configs": [],
        "extra_configs": [],
        "undeclared_nested_jars": [],
        "declared_nested_jars_missing": [],
        "classes_present": None,
        "targets": {},
        "handlers": [],
        "annotation_counts": {},
        "errors": [],
        "nested": [],
    }
    container = os.path.join(root, "scan", sanitize(label))
    try:
        safe_extract(jar, container)
    except Exception as exc:
        module["errors"].append(f"extraction refused: {exc}")
        return module
    meta = None
    try:
        meta = load_meta(container)
    except Exception as exc:
        module["errors"].append(f"unreadable fabric.mod.json: {exc}")
    if meta:
        module["fabric_mod_json"] = True
        module["mod_id"] = meta.get("id")
        module["mod_version"] = meta.get("version")
        module["mod_name"] = meta.get("name")
        module["environment"] = meta.get("environment", "*")
        module["depends"] = meta.get("depends", {})
        module["breaks"] = meta.get("breaks", {})
        module["conflicts"] = meta.get("conflicts", {})
        module["access_widener"] = meta.get("accessWidener")
        module["mixin_declarations"] = mixin_declarations(meta)
    module["classes_present"] = sum(
        1 for dirpath, _, files in os.walk(container) for f in files if f.endswith(".class"))
    declarations = {d["config"]: d["environment"] for d in module["mixin_declarations"] if d["config"]}
    all_configs = find_configs(container)
    if not module["mixin_declarations"] and all_configs:
        module["errors"].append(
            "fabric.mod.json declares no mixins; config files found by name only")
    for name in sorted(declarations):
        info, classes = config_info(container, name, True)
        info["environment"] = declarations[name]
        module["configs"].append(info)
        module["targets"].setdefault("__classes__", [])
        module["targets"]["__classes__"].extend(classes)
    for name in all_configs:
        if name in declarations:
            continue
        info, classes = config_info(container, name, False)
        module["extra_configs"].append(info)
        module["targets"].setdefault("__classes__", []).extend(classes)
    declared_jars = set()
    if meta:
        for entry in meta.get("jars", []) or []:
            name = entry.get("file") if isinstance(entry, dict) else None
            if name:
                declared_jars.add(os.path.basename(name))
    actual_jars = set(nested_jars(container))
    module["undeclared_nested_jars"] = sorted(actual_jars - declared_jars)
    module["declared_nested_jars_missing"] = sorted(declared_jars - actual_jars)
    classes = list(dict.fromkeys(module["targets"].pop("__classes__", [])))
    module["mixin_class_total"] = len(classes)
    futures = {pool.submit(inspect_class, container, cls): cls for cls in classes}
    for fut in concurrent.futures.as_completed(futures):
        cls = futures[fut]
        info = fut.result()
        for target in info.get("targets", []):
            module["targets"].setdefault(target, []).append(cls)
        for handler in info.get("handlers", []):
            handler["class"] = cls
            handler["has_target"] = bool(info.get("targets"))
            module["handlers"].append(handler)
        for kind, count in info.get("annotation_counts", {}).items():
            module["annotation_counts"][kind] = module["annotation_counts"].get(kind, 0) + count
        if info.get("error"):
            module["errors"].append(f"javap {cls}: {info['error']}")
    for target in module["targets"]:
        module["targets"][target] = sorted(set(module["targets"][target]))
    if depth < MAX_DEPTH:
        for name in sorted(actual_jars):
            child = os.path.join(container, "META-INF", "jars", name)
            digest = sha512_of(child)
            if digest in chain:
                module["errors"].append(f"nested jar cycle at {name}")
                continue
            if len(chain) >= MAX_CONTAINERS:
                module["errors"].append("nested container cap reached")
                break
            module["nested"].append(inspect_container(
                child, f"{label}!/META-INF/jars/{name}", root, depth + 1,
                chain | {digest}, pool))
    return module


def flatten(module):
    out = [module]
    for child in module.get("nested", []):
        out.extend(flatten(child))
    return out


def resolve_parents(all_modules):
    names = {os.path.basename(info["config"])
             for m in all_modules
             for info in m.get("configs", []) + m.get("extra_configs", [])}
    for m in all_modules:
        for info in m.get("configs", []) + m.get("extra_configs", []):
            parent = info.get("parent")
            if parent:
                info["parent_present_in_aggregate"] = os.path.basename(parent) in names


def resolve_plugins(all_modules, work):
    available = set()
    for m in all_modules:
        container = os.path.join(work, "scan", sanitize(m["label"]))
        for dirpath, _, files in os.walk(container):
            for name in files:
                if name.endswith(".class"):
                    rel = os.path.relpath(os.path.join(dirpath, name), container)
                    available.add(rel.replace(os.sep, "/"))
    for m in all_modules:
        env = m.get("environment")
        m["server_applicable"] = env != "client"
        m["server_mixin_total"] = 0
        for info in m.get("configs", []) + m.get("extra_configs", []):
            plugin = info.get("plugin")
            if plugin:
                rel = plugin.replace(".", "/") + ".class"
                info["plugin_present_in_aggregate"] = rel in available
            info["server_applicable"] = (
                m["server_applicable"] and info.get("environment") != "client")
            if info["server_applicable"]:
                m["server_mixin_total"] += info.get("server_class_count", 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default="/tmp/compat-manifest.json")
    ap.add_argument("--out", default="/tmp/compat-mixins.json")
    ap.add_argument("--root", default="/tmp/fabric-folia-compat-jars")
    ap.add_argument("--work", default="/tmp/ff-compat-scan")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--commit", default=None)
    args = ap.parse_args()
    with open(args.manifest) as fh:
        manifest = json.load(fh)
    if os.path.isdir(args.work):
        shutil.rmtree(args.work)
    os.makedirs(args.work, exist_ok=True)
    result = {"scan": manifest.get("scan"), "minecraft": manifest.get("minecraft"),
              "loader": manifest.get("loader"), "commit": args.commit,
              "manifest": os.path.abspath(args.manifest),
              "artifacts_root": os.path.abspath(args.root), "mods": []}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for entry in manifest["mods"]:
            if "jar" not in entry:
                result["mods"].append({"slug": entry["slug"],
                                       "error": entry.get("error", "unresolved")})
                continue
            path = entry["jar"]
            computed = sha512_of(path)
            module = inspect_container(path, entry["slug"], args.work, 0,
                                       {computed}, pool)
            module.update({
                "slug": entry["slug"],
                "declared_version": entry["version_number"],
                "manifest_sha512": entry["sha512"],
                "computed_sha512": computed,
                "sha512_match": computed == entry["sha512"],
                "version_id": entry["version_id"],
                "project_id": entry["project_id"],
                "url": entry["url"],
                "published": entry["published"],
                "role": entry["role"],
                "mods_minecraft": entry.get("game_versions"),
                "mods_loaders": entry.get("loaders"),
                "mods_dependencies": entry.get("dependencies"),
                "mods_all_26_2_versions": entry.get("all_26_2_versions"),
            })
            modules = flatten(module)
            module["container_count"] = len(modules)
            module["nested_module_count"] = len(modules) - 1
            module["aggregate_mixin_count"] = sum(m.get("mixin_class_total", 0) for m in modules)
            module["aggregate_server_mixin_count"] = sum(
                m.get("server_mixin_total", 0) for m in modules)
            module["aggregate_targets"] = sorted({t for m in modules for t in m.get("targets", {})})
            module["aggregate_missing_classes"] = sum(
                len(c.get("missing_classes", [])) for m in modules
                for c in m.get("configs", []) + m.get("extra_configs", []))
            module["aggregate_errors"] = sum(len(m.get("errors", [])) for m in modules)
            module["aggregate_handlers"] = sum(len(m.get("handlers", [])) for m in modules)
            result["mods"].append(module)
            print(f"{entry['slug']}: containers={len(modules)} "
                  f"mixins={module['aggregate_mixin_count']} "
                  f"handlers={module['aggregate_handlers']} "
                  f"targets={len(module['aggregate_targets'])} "
                  f"missing_classes={module['aggregate_missing_classes']} "
                  f"errors={module['aggregate_errors']} "
                  f"sha512={module['sha512_match']}", file=sys.stderr)
    all_modules = [m for mod in result["mods"] for m in flatten(mod)] if result["mods"] else []
    resolve_parents(all_modules)
    resolve_plugins(all_modules, args.work)
    for mod in result["mods"]:
        modules = flatten(mod)
        mod["aggregate_server_mixin_count"] = sum(
            m.get("server_mixin_total", 0) for m in modules)
    result["container_total"] = len(all_modules)
    with open(args.out, "w") as fh:
        json.dump(result, fh, indent=2, sort_keys=False)
    print(json.dumps({
        "containers": len(all_modules),
        "mods": {m["slug"]: {"containers": m.get("container_count"),
                             "mixins": m.get("aggregate_mixin_count"),
                             "server_mixins": m.get("aggregate_server_mixin_count"),
                             "handlers": m.get("aggregate_handlers"),
                             "targets": len(m.get("aggregate_targets", [])),
                             "missing_classes": m.get("aggregate_missing_classes"),
                             "sha512_match": m.get("sha512_match")}
                 for m in result["mods"]},
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
