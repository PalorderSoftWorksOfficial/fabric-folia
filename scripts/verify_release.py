import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MINIMUM_TESTS = {"common": 140, "api": 4, "fabric": 80}


def fail(message):
    raise SystemExit(message)


def properties(path):
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def collect_test_reports():
    result = {}
    for module, minimum in MINIMUM_TESTS.items():
        reports = sorted((ROOT / module / "build/test-results/test").glob("TEST-*.xml"))
        if not reports:
            fail(f"{module}: no JUnit XML reports were produced")
        totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        for report in reports:
            try:
                suite = ET.parse(report).getroot()
            except ET.ParseError as error:
                fail(f"{module}: invalid JUnit XML at {report}: {error}")
            for field in totals:
                totals[field] += int(suite.attrib.get(field, "0"))
            for case in suite.findall(".//testcase"):
                if case.find("skipped") is not None and "GpuParityTest" not in case.attrib.get("classname", ""):
                    fail(f"{module}: unexpected skipped test {case.attrib.get('classname')}.{case.attrib.get('name')}")
        if totals["tests"] < minimum:
            fail(f"{module}: expected at least {minimum} tests, found {totals['tests']}")
        if totals["failures"] or totals["errors"]:
            fail(f"{module}: test failures={totals['failures']} errors={totals['errors']}")
        result[module] = totals
    return result


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_mod_jar(path, version):
    if not path.is_file() or path.stat().st_size < 1024:
        fail(f"missing or invalid mod artifact: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                fail(f"{path.name}: archive integrity check failed")
            entries = set(archive.namelist())
            metadata = json.loads(archive.read("fabric.mod.json"))
            for key, value in {"id": "fabricfolia", "name": "Fabric-Folia", "version": version}.items():
                if metadata.get(key) != value:
                    fail(f"fabric.mod.json {key} expected {value!r}, got {metadata.get(key)!r}")
            dependencies = metadata.get("depends", {})
            for key, value in {
                "minecraft": "~26.2",
                "fabricloader": ">=0.19.5",
                "java": ">=25",
                "fabric-api": "0.160.0+26.2"
            }.items():
                if dependencies.get(key) != value:
                    fail(f"fabric.mod.json dependency {key} expected {value!r}, got {dependencies.get(key)!r}")
            icon = metadata.get("icon")
            if icon and icon not in entries:
                fail(f"fabric.mod.json icon resource is missing: {icon}")
            mixins = metadata.get("mixins", [])
            if not mixins:
                fail("fabric.mod.json has no mixin configuration")
            for mixin_path in mixins:
                mixin = json.loads(archive.read(mixin_path))
                if mixin.get("required") is not True or int(mixin.get("injectors", {}).get("defaultRequire", 0)) < 1:
                    fail(f"{mixin_path}: required/defaultRequire gate missing")
                package = mixin.get("package", "")
                for section in ("mixins", "server", "client"):
                    for class_name in mixin.get(section, []):
                        relative = "/".join(filter(None, (package.replace(".", "/"), class_name.replace(".", "/"))))
                        if relative + ".class" not in entries:
                            fail(f"{mixin_path}: declared class missing: {relative}.class")
            nested = [name for name in entries if name.startswith("META-INF/jars/") and name.endswith(".jar")]
            if not any(f"fabric-folia-api-{version}.jar" in name for name in nested):
                fail("mod artifact does not include the matching nested fabric-folia-api jar")
            if not any("common-" in name for name in nested):
                fail("mod artifact does not include the common engine jar")
    except (OSError, zipfile.BadZipFile, KeyError, json.JSONDecodeError) as error:
        fail(f"cannot inspect mod artifact {path}: {error}")


def inspect_api_jar(path):
    if not path.is_file() or path.stat().st_size < 1024:
        fail(f"missing or invalid standalone API artifact: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                fail(f"{path.name}: archive integrity check failed")
            entries = set(archive.namelist())
            if not any(name.endswith(".class") for name in entries):
                fail("API artifact contains no compiled classes")
            if any(name.startswith("net/minecraft/") or name.startswith("net/fabricmc/") for name in entries):
                fail("standalone API artifact contains Minecraft or Fabric Loader classes")
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"cannot inspect API artifact {path}: {error}")


def check_production_approval(version, commit):
    path = ROOT / "release/production-approval.json"
    if not path.is_file():
        fail("production release blocked: release/production-approval.json is missing")
    approval = json.loads(path.read_text(encoding="utf-8"))
    if approval.get("status") != "APPROVED":
        fail("production release blocked: approval status is not APPROVED")
    if approval.get("version") != version or approval.get("commit") != commit:
        fail("production approval does not match the current version and commit")
    evidence = approval.get("evidence", {})
    for key in ("runtime_soak", "compatibility_matrix", "independent_review"):
        relative = evidence.get(key)
        if not relative:
            fail(f"production approval missing evidence: {key}")
        candidate = (ROOT / relative).resolve()
        if ROOT.resolve() not in candidate.parents or not candidate.is_file():
            fail(f"production approval evidence is not a checked-in file: {relative}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-number", default=os.environ.get("BUILD_NUMBER", "local"))
    parser.add_argument("--production-release", action="store_true")
    args = parser.parse_args()
    props = properties(ROOT / "gradle.properties")
    version = props.get("mod_version")
    if not version:
        fail("gradle.properties is missing mod_version")
    try:
        commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, stderr=subprocess.DEVNULL
        ).strip()
        dirty = subprocess.check_output(
            ["git", "status", "--porcelain", "--untracked-files=normal"],
            cwd=ROOT, text=True, stderr=subprocess.DEVNULL
        ).strip()
    except subprocess.CalledProcessError:
        commit = "unknown"
        dirty = "unknown"
    if dirty:
        fail("release candidate must be built from a clean Git checkout; commit the intended source before packaging")

    tests = collect_test_reports()
    mod_jar = ROOT / f"fabric/build/libs/fabric-folia-{version}.jar"
    api_jar = ROOT / f"api/build/libs/fabric-folia-api-{version}.jar"
    mod_jars = sorted((ROOT / "fabric/build/libs").glob("*.jar"))
    if mod_jars != [mod_jar]:
        fail("fabric/build/libs must contain exactly one distributable Fabric mod JAR")
    api_jars = sorted(
        path for path in (ROOT / "api/build/libs").glob("*.jar")
        if not path.name.endswith("-sources.jar") and not path.name.endswith("-javadoc.jar")
    )
    if api_jars != [api_jar]:
        fail("api/build/libs must contain exactly one standalone API binary JAR")
    inspect_mod_jar(mod_jar, version)
    inspect_api_jar(api_jar)
    if args.production_release:
        check_production_approval(version, commit)

    output = ROOT / "build/release-candidate"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    artifacts = []
    for source in (mod_jar, api_jar):
        target = output / source.name
        shutil.copy2(source, target)
        artifacts.append({"file": target.name, "size": target.stat().st_size, "sha256": sha256(target)})
    totals = {
        name: sum(module[name] for module in tests.values())
        for name in ("tests", "failures", "errors", "skipped")
    }
    manifest = {
        "project": "Fabric-Folia",
        "version": version,
        "git_commit": commit,
        "build_number": str(args.build_number),
        "release_status": "production-approved" if args.production_release else "candidate",
        "source_checkout_clean": True,
        "tests": tests,
        "test_totals": totals,
        "artifacts": artifacts
    }
    (output / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS").write_text(
        "".join(f"{item['sha256']}  {item['file']}\n" for item in artifacts),
        encoding="utf-8"
    )
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
