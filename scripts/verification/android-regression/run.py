"""Fixed, opt-in offline instrumentation. Never builds or starts an emulator."""
import sys
sys.dont_write_bytecode = True

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
from datetime import datetime, timezone
from uuid import uuid4

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # Works under Python -I without inherited PYTHONPATH.
from validate import (cleanup_allowed, selection_from_text, validate_debug_apk,
                      validate_idle_instrumentation, validate_instrumentation,
                      validate_test_manifest)

ROOT = HERE.parents[2]
SERIAL = "emulator-5554"
AVD = "DomiDex_R27_Baseline_API36"
APP = "com.dominik.cardvault"
TEST = APP + ".test"
INSTRUMENTATION = TEST + "/androidx.test.runner.AndroidJUnitRunner"
APK_PATHS = ("app/build/outputs/apk/debug/app-debug.apk",
             "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
TIMEOUT = 1200
LOG_LIMIT = 16 * 1024 * 1024


def options(arguments):
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="Read-only prerequisites; no install/run/output directory")
    mode.add_argument("--run", action="store_true", help="Install two standard debug APKs and run the fixed 371-case matrix")
    parser.add_argument("--sdk-root", type=Path, default=Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk")
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("ProgramFiles", "")) / "Android/Android Studio/jbr")
    return parser.parse_args(arguments)


def clean_environment(source):
    # No inherited ADB endpoints/serial, Java options, Python startup/site hooks,
    # proxies, cloud credentials, signing variables or application configuration.
    allowed = {"SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "TEMP", "TMP"}
    result = {key: value for key, value in source.items() if key.upper() in allowed}
    system = result.get("SystemRoot", result.get("SYSTEMROOT", r"C:\Windows"))
    result["PATH"] = str(Path(system) / "System32")
    return result


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def required_file(path, within=None):
    resolved = path.resolve(strict=True)
    if not resolved.is_file() or (within is not None and not resolved.is_relative_to(within.resolve())):
        raise ValueError("Required ordinary file missing or outside expected root: " + str(path))
    return resolved


def source_hashes():
    # Build outputs, ignored credentials and local.properties are intentionally not
    # read. This records local sources, not a claim that old APKs match this tree.
    paths = [ROOT / name for name in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")]
    for directory in (ROOT / "app/src", ROOT / "gradle", HERE):
        paths.extend(path for path in directory.rglob("*") if path.is_file() and "__pycache__" not in path.parts)
    return {path.relative_to(ROOT).as_posix(): file_hash(required_file(path, ROOT)) for path in sorted(set(paths))}


def aggregate_hash(values):
    return hashlib.sha256(json.dumps(values, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def require_existing_adb_server():
    # Query only the existing local daemon. Never invoke start-server/kill-server,
    # and reject a protocol mismatch before adb could replace another user's daemon.
    with socket.create_connection(("127.0.0.1", 5037), timeout=2) as connection:
        def receive(count):
            value = b""
            while len(value) < count:
                block = connection.recv(count - len(value))
                if not block:
                    raise ValueError("Incomplete local ADB protocol response")
                value += block
            return value
        connection.sendall(b"000chost:version")
        if receive(4) != b"OKAY" or receive(4) != b"0004" or receive(4) != b"0029":
            raise ValueError("An already running local ADB protocol-41 server is required")


class Runner:
    def __init__(self, args):
        self.args = args
        self.env = clean_environment(os.environ)
        self.adb = required_file(args.sdk_root / "platform-tools/adb.exe", args.sdk_root)
        build_tools = args.sdk_root / "build-tools/36.0.0"
        self.aapt = required_file(build_tools / "aapt2.exe", args.sdk_root)
        self.signer = required_file(build_tools / "lib/apksigner.jar", args.sdk_root)
        self.java = required_file(args.java_home / "bin/java.exe", args.java_home)
        self.apks = [required_file(ROOT / name, ROOT / "app/build/outputs/apk") for name in APK_PATHS]
        self.selection = selection_from_text((HERE / "selection.json").read_text(encoding="utf-8"))
        self.started = False
        self.run_token = uuid4().hex

    def command(self, arguments, timeout=20, accepted=(0,)):
        result = subprocess.run([str(value) for value in arguments], cwd=ROOT, env=self.env,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                encoding="utf-8", errors="replace", timeout=timeout, shell=False,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode not in accepted:
            raise RuntimeError("Local command failed: " + Path(arguments[0]).name + "\n" + result.stdout[:4000])
        return result.stdout.strip()

    def device(self, *arguments, timeout=20, accepted=(0,)):
        return self.command([self.adb, "-s", SERIAL, *arguments], timeout, accepted)

    def identity(self):
        if (self.device("get-state") != "device" or
                self.device("shell", "getprop", "ro.boot.qemu.avd_name") != AVD or
                self.device("shell", "getprop", "ro.kernel.qemu") != "1" or
                self.device("shell", "getprop", "ro.build.version.sdk") != "36"):
            raise ValueError("Only the fixed API-36 emulator/AVD is allowed; no device was changed")

    def offline(self):
        self.identity()
        if (self.device("shell", "settings", "get", "global", "airplane_mode_on") != "1" or
                self.device("shell", "settings", "get", "global", "wifi_on") != "0" or
                not re.search(r"(?m)^\s*Active default network: none\s*$", self.device("shell", "dumpsys", "connectivity"))):
            raise ValueError("Offline precondition missing; this runner never changes connectivity")
        if self.device("reverse", "--list"):
            raise ValueError("Existing reverse mapping; nothing will be removed")

    def idle(self):
        validate_idle_instrumentation(self.device("shell", "dumpsys", "activity", "processes"))
        for package in (APP, TEST):
            if self.device("shell", "pidof", package, accepted=(0, 1)):
                raise ValueError("An existing app/test process is running; it will not be stopped")

    def preflight(self):
        version = self.command([self.adb, "version"])
        if not re.search(r"(?m)^Android Debug Bridge version 1\.0\.41\s*$", version):
            raise ValueError("Unreviewed ADB protocol")
        require_existing_adb_server()
        self.offline()
        self.idle()
        certificates = []
        for apk, package in zip(self.apks, (APP, TEST)):
            badging = self.command([self.aapt, "dump", "badging", apk])
            signature = self.command([self.java, "-jar", self.signer, "verify", "--verbose", "--print-certs", apk])
            certificates.append(validate_debug_apk(badging, signature, package))
        if certificates[0] != certificates[1]:
            raise ValueError("Both standard debug APKs must have the same certificate")
        validate_test_manifest(self.command([self.aapt, "dump", "xmltree", self.apks[1], "--file", "AndroidManifest.xml"]))
        return {"serial": SERIAL, "avd": AVD, "offline": True, "reverseEmpty": True,
                "certificateSha256": certificates[0], "adb": version,
                "tools": {str(path): file_hash(path) for path in (self.adb, self.aapt, self.signer, self.java)}}

    def instrumentation_command(self):
        return [str(self.adb), "-s", SERIAL, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                ",".join(self.selection["selectors"]), "-e", "domidexRegressionRunId", self.run_token, INSTRUMENTATION]

    def instrument(self, logfile):
        with logfile.open("wb") as stream:
            process = subprocess.Popen(self.instrumentation_command(), cwd=ROOT, env=self.env,
                                       stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                                       shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
            self.started = True
            try:
                deadline = time.monotonic() + TIMEOUT
                while process.poll() is None:
                    if time.monotonic() >= deadline or logfile.stat().st_size > LOG_LIMIT:
                        raise TimeoutError("Own instrumentation exceeded its time/output budget")
                    time.sleep(0.2)
                if process.returncode != 0 or logfile.stat().st_size > LOG_LIMIT:
                    raise RuntimeError("Own instrumentation command failed")
            finally:
                if process.poll() is None:
                    process.kill()  # Only this exact Popen child, never kill-server/process-name matching.
                process.wait(timeout=10)

    def failure_cleanup(self):
        if not self.started:
            return "not-started-no-stop"
        self.identity()
        state = self.device("shell", "dumpsys", "activity", "processes")
        if not cleanup_allowed(self.started, True, state, self.run_token):
            raise ValueError("Exact active invocation ownership unconfirmed; refusing package cleanup")
        for package in (APP, TEST):
            self.device("shell", "am", "force-stop", package)
        return "own-run-packages-stopped"

    def run(self):
        before = {name: file_hash(path) for name, path in zip(APK_PATHS, self.apks)}
        sources = source_hashes()
        preflight = self.preflight()
        if self.args.check:
            print(json.dumps({"check": "passed", **preflight, "apks": before,
                              "sourceSha256": aggregate_hash(sources), "expectedTests": 371}, indent=2))
            return 0
        directory = ROOT / "artifacts/android-regression" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8])
        directory.mkdir(parents=True, exist_ok=False)
        manifest = {"schema": 1, "startedAt": datetime.now(timezone.utc).isoformat(), "preflight": preflight,
                    "runToken": self.run_token,
                    "apksBefore": before, "sources": sources, "sourceSha256": aggregate_hash(sources),
                    "selectionSha256": file_hash(HERE / "selection.json"), "installed": [], "exitCode": 1,
                    "scope": "APK hashes identify installed files; source hashes do not prove APK/source reproducibility"}
        print("Local regression evidence: " + str(directory), flush=True)
        try:
            self.offline()
            self.idle()
            for name, apk in zip(APK_PATHS, self.apks):
                if file_hash(apk) != before[name]:
                    raise ValueError("APK changed before installation")
                # Fixed -t permits AndroidTest's testOnly manifest. It is not an
                # alternate-APK fallback; both exact files already passed all guards.
                output = self.device("install", "-r", "-t", str(apk), timeout=120)
                with (directory / "installation.log").open("a", encoding="utf-8") as receipt:
                    receipt.write(name + " SHA256=" + before[name] + "\n" + output + "\n")
                if not re.search(r"(?m)^Success\s*$", output) or file_hash(apk) != before[name]:
                    raise ValueError("Installation not confirmed or APK changed")
                manifest["installed"].append({"file": name, "sha256": before[name], "serial": SERIAL})
            self.offline()
            self.idle()
            self.instrument(directory / "instrumentation.log")
            manifest["tests"] = validate_instrumentation((directory / "instrumentation.log").read_text(encoding="utf-8"), self.selection["expected_ids"])
            self.offline()
            manifest["exitCode"] = 0
        except (Exception, KeyboardInterrupt) as error:
            manifest["error"] = type(error).__name__ + ": " + str(error)
            try:
                manifest["cleanup"] = self.failure_cleanup()
            except Exception as cleanup_error:
                manifest["cleanupError"] = str(cleanup_error)
        finally:
            try:
                manifest["apksAfter"] = {name: file_hash(path) for name, path in zip(APK_PATHS, self.apks)}
                manifest["sourcesAfterSha256"] = aggregate_hash(source_hashes())
                manifest["inputsUnchanged"] = (manifest["apksAfter"] == before and manifest["sourcesAfterSha256"] == manifest["sourceSha256"])
                self.offline()
                manifest["afterOfflineNoReverse"] = True
                if not manifest["inputsUnchanged"]:
                    manifest["exitCode"] = 1
            except Exception as error:
                manifest["finalCheckError"] = str(error)
                manifest["exitCode"] = 1
            manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
            (directory / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print("Manifest: " + str(directory / "manifest.json"), flush=True)
        return manifest["exitCode"]


def main(arguments=None):
    if sys.platform != "win32" or not sys.flags.isolated or not sys.flags.dont_write_bytecode:
        raise ValueError("Use preinstalled Python 3.11+ on Windows with python -I -B; no startup hooks")
    if sys.version_info < (3, 11):
        raise ValueError("Preinstalled Python 3.11+ required")
    return Runner(options(sys.argv[1:] if arguments is None else arguments)).run()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (Exception, KeyboardInterrupt) as failure:
        print(type(failure).__name__ + ": " + str(failure), file=sys.stderr)
        raise SystemExit(1)
