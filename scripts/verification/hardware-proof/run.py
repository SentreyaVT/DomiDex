"""Opt-in isolated Redmi proof. Never builds, changes networking or touches ordinary app data."""
import sys
sys.dont_write_bytecode = True
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
from datetime import datetime, timezone
from uuid import uuid4

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = ROOT / "artifacts/release27-engineering-20261003/hardware-proof"
SDK = Path("C:/Users/Dominik/AppData/Local/Android/Sdk")
JAVA = Path("C:/Program Files/Android/Android Studio/jbr/bin/java.exe")
SERIAL = "e30bd3ce"
ORDINARY = "com.dominik.cardvault"
TIMEOUT, LOG_LIMIT = 1200, 16 * 1024 * 1024
APK_PATHS = ("outputs/apk/debug/app-debug.apk", "outputs/apk/androidTest/debug/app-debug-androidTest.apk")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def ordinary_file(path, parent):
    require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(parent.resolve()),
            "Required ordinary file outside owned scope: " + str(path))
    current = path
    while current != parent:
        require(not current.is_symlink(), "Linked input path")
        current = current.parent
    return path


def module(path, name):
    ordinary_file(path, ROOT)
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def load_helpers():
    contract = module(HERE / "contracts.py", "hardware_contracts")
    shared_path = ROOT / "scripts/verification/android-regression/run.py"
    require(sha(shared_path) == "c474c65ea497d942dc8e2798760359a271d34490b66f132835b401b8ac2baa1c",
            "Reviewed shared runner changed")
    shared = module(shared_path, "hardware_shared_runner")
    return contract, shared, module(HERE / "build.py", "hardware_builder")


class Runner:
    def __init__(self, build_dir):
        self.contract, self.shared, self.builder = load_helpers()
        self.directory = build_dir
        self.env = self.shared.clean_environment(os.environ)
        self.adb = ordinary_file(SDK / "platform-tools/adb.exe", SDK)
        self.aapt = ordinary_file(SDK / "build-tools/36.0.0/aapt2.exe", SDK)
        self.signer = ordinary_file(SDK / "build-tools/36.0.0/lib/apksigner.jar", SDK)
        ordinary_file(JAVA, JAVA.parent)
        self.token = uuid4().hex
        self.apks, self.owned, self.attempted = {}, set(), set()
        self.receipts = []

    def command(self, args, timeout=30, accepted=(0,)):
        result = subprocess.run([str(v) for v in args], cwd=ROOT, env=self.env,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=timeout, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
        require(len(result.stdout) <= LOG_LIMIT, "Command output exceeded16MiB")
        text = result.stdout.decode("utf-8", errors="strict")
        require(result.returncode in accepted, "Command failed: " + text[:300])
        return text

    def adb_command(self, *args, **kwargs):
        self.shared.require_existing_adb_server()
        return self.command([self.adb, "-s", SERIAL, *args], **kwargs)

    def shell(self, *args, **kwargs):
        return self.adb_command("shell", "-T", *args, **kwargs)

    def build_guard(self):
        directory = self.directory
        require(directory.is_absolute() and directory.parent == BASE and not directory.is_symlink()
                and directory.resolve() == directory, "Exact non-aliased hardware build directory required")
        self.package = self.contract.package_for(directory.name)
        self.test_package = self.package + ".test"
        result = json.loads(ordinary_file(directory / "build-result.json", directory).read_text(encoding="utf-8"))
        before = json.loads(ordinary_file(directory / "before.json", directory).read_text(encoding="utf-8"))
        after = json.loads(ordinary_file(directory / "after.json", directory).read_text(encoding="utf-8"))
        require(result.get("status") == "PASS" and result.get("exitCode") == 0 and result.get("proofExitCode") == 0
                and result.get("buildFailureType") is None and result.get("ownedProcessCleanupError") is None
                and result.get("timedOut") is False and result.get("inputsAndOldOutputsUnchanged") is True
                and result.get("proofClassesVerified") is True and result.get("packagedManifestVerified") is True
                and result.get("debugCertificateSha256") == self.contract.CERT_SHA256, "No successful isolated build")
        for record in (result, before):
            require(record.get("runId") == directory.name and record.get("package") == self.package
                    and record.get("testPackage") == self.test_package
                    and record.get("expectedTests") == self.contract.EXPECTED
                    and record.get("selectors") == self.contract.SELECTORS, "Build identity/selection mismatch")
        require({key: before[key] for key in ("inputs", "preserved")} == after == self.builder.snapshot(),
                "Build inputs or protected original outputs changed")
        require(result.get("injectedSources") == before.get("injectedSources")
                and len(result["injectedSources"]) == 7, "Source injection receipt mismatch")
        for item, (name, methods) in zip(result["injectedSources"], self.contract.CLASSES):
            relative = "app/src/androidTest/java/" + name.replace(".", "/") + ".kt"
            copied = "injected-source/" + name.replace(".", "/") + ".kt"
            require(item.get("source") == relative and item.get("className") == name
                    and item.get("methods") == list(methods) and item.get("relativeCopied") == copied,
                    "Unexpected injected test source")
            require(sha(ordinary_file(ROOT / relative, ROOT)) == item["sha256"] ==
                    sha(ordinary_file(directory / copied, directory)), "Original/copied test hash changed")
        require(set(result.get("artifacts", {})) == set(APK_PATHS), "Expected exactly two APK artifacts")
        for package, relative in zip((self.package, self.test_package), APK_PATHS):
            path = ordinary_file(directory / "app-build" / relative, directory)
            expected = result["artifacts"][relative]
            require(expected == {"bytes": path.stat().st_size, "sha256": sha(path)}, "APK artifact changed")
            self.apks[package] = (path, expected["sha256"])
        self.build_receipt = result
        self.frozen_receipts = {name: sha(directory / name) for name in
            ("before.json", "after.json", "build-result.json", "apk-inspection.json")}

    def verify_local_apks(self):
        for package, (path, expected) in self.apks.items():
            require(sha(path) == expected, "APK changed before inspection")
            badging = self.command([self.aapt, "dump", "badging", path])
            signature = self.command([JAVA, "-jar", self.signer, "verify", "--verbose", "--print-certs", path])
            self.contract.validate_debug_apk(badging, signature, package)
            tree = self.command([self.aapt, "dump", "xmltree", path, "--file", "AndroidManifest.xml"])
            self.contract.validate_manifest(tree, package, test=package == self.test_package, target=self.package)

    def identity(self):
        require(self.adb_command("get-state").strip() == "device", "Fixed Redmi unavailable")
        features = self.adb_command("features")
        require(re.fullmatch(r"(?:[a-z0-9_]+\r?\n)+", features) is not None
                and features.splitlines().count("shell_v2") == 1, "Reliable shell exit status required")
        expected = {"ro.product.model": "24115RA8EG", "ro.build.version.sdk": "36",
                    "ro.build.version.release": "16", "ro.product.cpu.abi": "arm64-v8a",
                    "ro.product.device": "amethyst"}
        actual = {key: self.shell("getprop", key).strip() for key in expected}
        require(actual == expected, "Fixed physical Redmi identity changed")
        require(self.shell("getprop", "ro.kernel.qemu").strip() in ("", "0"), "Emulator forbidden")
        require(self.shell("getconf", "PAGE_SIZE").strip() == "4096", "Expected physical4KiB pages")
        require(self.shell("am", "get-current-user").strip() == "0", "Only primary Android user allowed")
        return {"serial": SERIAL, **actual, "pageSize": 4096, "user": 0}

    def idle(self):
        self.contract.shared_contracts().validate_idle_instrumentation(self.shell("dumpsys", "activity", "processes"))

    def absent(self, package):
        require(package in (self.package, self.test_package), "Absence probe outside isolated pair")
        text = self.shell("pm", "list", "packages", "-u", "--user", "all", package)
        lines = text.splitlines()
        require(all(re.fullmatch(r"package:[A-Za-z0-9_.]+", line) for line in lines), "Unknown package inventory")
        require("package:" + package not in lines, "Preexisting proof package/data must never be overwritten")

    def installed(self, package):
        require(package in (ORDINARY, self.package, self.test_package), "Installed snapshot outside exact package scope")
        text = self.shell("pm", "path", "--user", "0", package)
        paths = [line.removeprefix("package:") for line in text.splitlines()]
        require(paths and len(paths) == len(set(paths)) and all(re.fullmatch(r"/data/app/[A-Za-z0-9_./=+-]+\.apk", p)
                for p in paths) and all(line.startswith("package:") for line in text.splitlines()), "Unknown installed APK paths")
        hashes = {}
        for path in sorted(paths):
            text = self.shell("sha256sum", path)
            match = re.fullmatch(r"([a-f0-9]{64})  " + re.escape(path) + r"\r?\n", text)
            require(match is not None, "Installed APK hash could not be verified")
            hashes[path] = match[1]
        versions = self.shell("dumpsys", "package", package)
        codes = set(re.findall(r"(?m)^\s*versionCode=(\d+)(?:\s.*)?$", versions))
        names = set(re.findall(r"(?m)^\s*versionName=([^\r\n]+)\r?$", versions))
        require(len(codes) == len(names) == 1, "Unambiguous installed version required")
        return {"package": package, "pathsAndHashes": hashes, "versionCode": codes.pop(), "versionName": names.pop()}

    def own_installed(self, package):
        require(package in self.attempted and package in self.apks, "No ownership outside this installation attempt")
        result = self.installed(package)
        require(list(result["pathsAndHashes"].values()) == [self.apks[package][1]], "Installed isolated APK hash mismatch")
        self.owned.add(package)
        return result

    def install(self, package):
        self.identity()
        self.idle()
        self.absent(package)
        require(sha(self.apks[package][0]) == self.apks[package][1], "APK changed before install")
        self.attempted.add(package)
        # No -r: even a concurrent installation cannot be overwritten.
        output = self.adb_command("install", "-t", "--no-streaming", "--user", "0", self.apks[package][0], timeout=180)
        self.receipts.append({"action": "install", "package": package, "output": output})
        require(re.findall(r"(?m)^Success\r?$", output) == ["Success"], "No exact successful install receipt")
        self.own_installed(package)

    def instrument(self, output):
        args = [self.adb, "-s", SERIAL, "shell", "-T", "am", "instrument", "-w", "-r",
                "-e", "hardwareProofRun", self.directory.name, "-e", "hardwareProofMode", "isolated-redmi33",
                "-e", "domidexRegressionRunId", self.token, "-e", "class", ",".join(self.contract.SELECTORS),
                self.test_package + "/" + self.contract.RUNNER]
        process = None
        with output.open("xb") as log:
            try:
                self.shared.require_existing_adb_server()
                process = subprocess.Popen([str(v) for v in args], cwd=ROOT, env=self.env,
                    stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                    shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
                deadline = time.monotonic() + TIMEOUT
                while process.poll() is None:
                    require(output.stat().st_size <= LOG_LIMIT, "Instrumentation exceeded16MiB")
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Own instrumentation exceeded20minutes")
                    time.sleep(.1)
                require(output.stat().st_size <= LOG_LIMIT, "Instrumentation exceeded16MiB")
                require(process.returncode == 0, "Instrumentation host process failed")
            finally:
                if process is not None and process.poll() is None:
                    # Terminate only our host ADB client; owned device cleanup follows in finally.
                    process.kill()
                    process.wait(timeout=10)
        return self.contract.validate_log(output.read_text(encoding="utf-8"),
            self.contract.shared_contracts().validate_instrumentation)

    def cleanup(self):
        errors = []
        for package in (self.test_package, self.package):
            if package not in self.attempted:
                continue
            try:
                self.identity()
                # Also reconciles a successful install whose host receipt was interrupted.
                self.own_installed(package)
                self.shell("am", "force-stop", "--user", "0", package)
                self.own_installed(package)
                text = self.adb_command("uninstall", package, timeout=60)
                require(text.strip() == "Success", "Owned package uninstall failed")
                self.absent(package)
                self.receipts.append({"action": "uninstall", "package": package, "output": text})
            except BaseException as error:
                errors.append({"package": package, "error": type(error).__name__ + ": " + str(error)})
        return errors

    def run(self, execute):
        self.build_guard()
        self.verify_local_apks()
        device = self.identity()
        self.idle()
        for package in (self.package, self.test_package):
            self.absent(package)
        normal_before = self.installed(ORDINARY)
        frozen = self.builder.snapshot()
        if not execute:
            return {"status": "CHECK_ONLY", "device": device, "ordinaryApp": normal_before,
                    "buildDir": str(self.directory), "expectedTests": self.contract.EXPECTED}
        directory = self.directory / ("runtime-" + self.token)
        directory.mkdir(exist_ok=False)
        result = {"status": "FAIL", "runId": self.token, "buildRunId": self.directory.name,
                  "startedAt": datetime.now(timezone.utc).isoformat(), "device": device,
                  "ordinaryBefore": normal_before, "expectedTests": self.contract.EXPECTED,
                  "buildReceipt": self.build_receipt, "failure": None}
        try:
            for package in (self.package, self.test_package):
                self.install(package)
            self.identity()
            self.idle()
            for package in (self.package, self.test_package):
                self.own_installed(package)
            require(self.builder.snapshot() == frozen, "Inputs changed before instrumentation")
            result["instrumentation"] = self.instrument(directory / "instrumentation.log")
        except BaseException as error:
            result["failure"] = type(error).__name__ + ": " + str(error)
        finally:
            result["cleanupErrors"] = self.cleanup()
            try:
                require(self.identity() == device, "Device changed")
                self.idle()
                result["ordinaryAfter"] = self.installed(ORDINARY)
                require(result["ordinaryAfter"] == normal_before, "Ordinary APK paths/hash/version changed")
                require(self.builder.snapshot() == frozen, "Inputs/original outputs changed")
                require(all(sha(self.directory / name) == value for name, value in self.frozen_receipts.items()),
                        "Build evidence changed")
                require(all(sha(path) == digest for path, digest in self.apks.values()), "Proof APK changed")
                result["preservationVerified"] = True
            except BaseException as error:
                result["postFailure"] = type(error).__name__ + ": " + str(error)
            result["receipts"] = self.receipts
            result["finishedAt"] = datetime.now(timezone.utc).isoformat()
            if (result["failure"] is None and not result["cleanupErrors"] and not result.get("postFailure")
                    and result.get("instrumentation", {}).get("passed") == 33):
                result["status"] = "PASS"
            with (directory / "result.json").open("x", encoding="utf-8") as stream:
                json.dump(result, stream, indent=2)
        return result


def main():
    require(sys.platform == "win32" and sys.version_info >= (3, 11) and sys.flags.isolated
            and sys.flags.dont_write_bytecode, "Use Windows Python3.11+ -I -B")
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--run", action="store_true")
    parser.add_argument("--build-dir", type=Path, required=True)
    args = parser.parse_args()
    result = Runner(args.build_dir).run(args.run)
    print(json.dumps(result, indent=2))
    return 0 if result["status"] in ("PASS", "CHECK_ONLY") else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (Exception, KeyboardInterrupt) as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr)
        raise SystemExit(1)
