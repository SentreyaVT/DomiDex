"""Offline, version-exact license/provenance archiving for native dependencies.

This is an evidence inventory, not a legal clearance. Unknown revisions and
missing license texts remain explicit. Release archiving never downloads files;
the optional evidence preparation step is separate and uses pinned public bytes.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

REGISTRY_PATH = Path(__file__).with_name("native-providers.json")
MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
_LICENSE_NAME = re.compile(r"^(?:licenses?|notices?|copying|copyright|authors)(?:[._-].*)?$", re.I)
_SENSITIVE_NAME = re.compile(r"(?:keystore|credential|secrets?|token|password|\.p12$|\.jks$|\.pem$)", re.I)


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_registry() -> dict:
    registry = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
    if registry.get("schema_version") != 1:
        raise ValueError("Unsupported native-provider registry schema")
    return registry


def _relative(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if (not value or "\\" in value or ":" in value or "\x00" in value
            or path.is_absolute() or ".." in path.parts or "." in value.split("/")):
        raise ValueError("Unsafe relative evidence path")
    return path


def _inside(root: Path, relative: str) -> Path:
    path = root.joinpath(*_relative(relative).parts)
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Evidence path escapes its allowed root")
    return path


def _public_document(data: bytes, name: str) -> None:
    if len(data) > MAX_DOCUMENT_BYTES:
        raise ValueError("License/POM document exceeds the size limit")
    if _SENSITIVE_NAME.search(PurePosixPath(name).name):
        raise ValueError("Sensitive file name is not permitted in license archives")
    if re.search(rb"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----", data):
        raise ValueError("Private-key data is not permitted in license archives")


def _put(root: Path, relative: str, data: bytes) -> Path:
    _public_document(data, relative)
    target = _inside(root, relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if target.read_bytes() != data:
            raise ValueError("Refusing to overwrite different archived evidence")
    else:
        # Exclusive creation protects existing artifacts even if another process writes.
        with target.open("xb") as stream:
            stream.write(data)
    return target


def _pom_matches(data: bytes, coordinate: str) -> bool:
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        return False
    try:
        root = ET.fromstring(data)
        def text(name: str) -> str:
            node = root.find("{*}" + name)
            if node is None and name in {"groupId", "version"}:
                node = root.find("{*}parent/{*}" + name)
            return (node.text or "").strip() if node is not None else ""
        return ":".join(text(key) for key in ("groupId", "artifactId", "version")) == coordinate
    except ET.ParseError:
        return False


def _is_license(name: str) -> bool:
    leaf = PurePosixPath(name).name
    return bool(_LICENSE_NAME.match(leaf)) or leaf.lower() in {
        "third_party_licenses.txt", "third_party_licenses.json"
    }


def archive_licenses(output_dir: Path, dependencies: list[dict],
                     evidence_root: Path | None = None) -> dict:
    """Archive licenses for actual, already AAB-matched dependency artifacts.

    dependencies: coordinate, aar_path, optionally aar_sha256 and pom_path.
    The caller must establish which AAR supplies the actual shipped native bytes.
    No cache walking, network, home directories, credentials or arbitrary copying.
    Files are stored below output_dir/licenses. Safe repeat writes are idempotent.
    """
    registry = load_registry()
    root = Path(output_dir) / "licenses"
    root.mkdir(parents=True, exist_ok=True)
    report: dict = {"schema_version": 1, "complete": True, "files": [],
                    "dependencies": [], "warnings": []}
    records = {row["cache_path"]: row for row in registry["external_evidence"]}
    seen: dict[str, str] = {}
    for dependency in sorted(dependencies, key=lambda value: value["coordinate"]):
        coordinate = dependency["coordinate"]
        if not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+", coordinate):
            raise ValueError("Expected an exact Maven group:artifact:version coordinate")
        aar = Path(dependency["aar_path"])
        if aar.suffix.lower() != ".aar":
            raise ValueError("License input must be the resolved original AAR")
        with aar.open("rb") as stream:
            aar_hash = hashlib.file_digest(stream, "sha256").hexdigest()
        if dependency.get("aar_sha256") and dependency["aar_sha256"].lower() != aar_hash:
            raise ValueError("Resolved dependency AAR hash changed")
        if coordinate in seen:
            if seen[coordinate] != aar_hash:
                raise ValueError("Conflicting artifacts for the same exact dependency")
            continue
        seen[coordinate] = aar_hash
        provider = registry["providers"].get(coordinate)
        prefix = coordinate.replace(":", "/")
        item = {"coordinate": coordinate, "aar_sha256": aar_hash,
                "registry_known": provider is not None, "files": [], "warnings": []}
        if provider:
            item["provider"] = provider
            item["warnings"].extend(provider.get("unresolved_checks", []))
        else:
            item["warnings"].append("No reviewed license/symbol provenance for this exact dependency version; do not reuse another version's evidence.")

        def add(relative: str, data: bytes, source: dict) -> None:
            path = prefix + "/" + relative
            _put(root, path, data)
            row = {"path": "licenses/" + path, "sha256": _digest(data),
                   "bytes": len(data), "coordinate": coordinate, "source": source}
            report["files"].append(row)
            item["files"].append(row["path"])

        found = set()
        with zipfile.ZipFile(aar) as archive:
            for member in archive.infolist():
                if member.is_dir() or not _is_license(member.filename):
                    continue
                _relative(member.filename)
                if member.filename in found:
                    raise ValueError("Duplicate license entry in dependency AAR")
                found.add(member.filename)
                if member.file_size > MAX_DOCUMENT_BYTES or (member.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("Oversized or symbolic-link AAR license entry")
                add("aar/" + member.filename, archive.read(member),
                    {"kind": "aar-member", "member": member.filename, "aar_sha256": aar_hash})
        for expected in (provider or {}).get("expected_aar_licenses", []):
            if expected not in found:
                item["warnings"].append("Expected AAR license missing: " + expected)

        pom_written = False
        if dependency.get("pom_path"):
            pom = Path(dependency["pom_path"])
            if pom.suffix.lower() != ".pom":
                raise ValueError("Original POM path must use the .pom extension")
            data = pom.read_bytes()
            if not _pom_matches(data, coordinate):
                raise ValueError("POM does not match the exact resolved dependency")
            add("original.pom", data, {"kind": "resolved-original-pom"})
            pom_written = True
        for evidence_id in (provider or {}).get("external_evidence", []):
            record = records[evidence_id]
            if record["kind"] == "pom" and pom_written:
                continue
            source = _inside(Path(evidence_root), evidence_id) if evidence_root else None
            if source is None or not source.is_file():
                item["warnings"].append("Pinned license evidence unavailable: " + evidence_id)
                continue
            data = source.read_bytes()
            if _digest(data) != record["sha256"]:
                raise ValueError("Pinned license evidence hash mismatch: " + evidence_id)
            if record["kind"] == "pom" and not _pom_matches(data, coordinate):
                raise ValueError("Pinned POM coordinate mismatch")
            add("upstream/" + evidence_id, data, {
                key: record[key] for key in ("url", "member", "kind", "scope") if key in record
            })
        if not item["files"]:
            item["warnings"].append("No original license text or POM was available.")
        report["dependencies"].append(item)
        report["warnings"].extend(coordinate + ": " + warning for warning in item["warnings"])
    report["complete"] = not report["warnings"]
    report["scope"] = "Native license evidence only; unresolved provenance is not legal clearance. No inference that every SDK license inventory component is linked."
    _put(root, "manifest.json", (json.dumps(report, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    return report


class _RemoteZip(io.RawIOBase):
    """Bounded HTTPS range reader, never a full NDK/SDK installation/download."""
    def __init__(self, url: str):
        self.url = url
        self.position = 0
        self.length = 0
        _, self.length = self._range(0, 0, initial=True)

    def _range(self, start: int, end: int, initial: bool = False) -> tuple[bytes, int]:
        request = urllib.request.Request(self.url, headers={
            "Range": f"bytes={start}-{end}", "Accept-Encoding": "identity"
        })
        with urllib.request.urlopen(request, timeout=30) as response:
            match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("Content-Range", ""))
            if response.status != 206 or not match or (int(match[1]), int(match[2])) != (start, end):
                raise ValueError("Official archive did not honor the bounded byte range")
            total = int(match[3])
            if not initial and total != self.length:
                raise ValueError("Remote archive size changed during extraction")
            data = response.read(end - start + 2)
            if len(data) != end - start + 1:
                raise ValueError("Truncated or oversized remote archive response")
            return data, total

    def seekable(self) -> bool:
        return True

    def readable(self) -> bool:
        return True

    def tell(self) -> int:
        return self.position

    def seek(self, offset: int, whence: int = 0) -> int:
        position = offset if whence == 0 else self.position + offset if whence == 1 else self.length + offset
        if position < 0 or position > self.length or whence not in (0, 1, 2):
            raise ValueError("Remote archive seek out of bounds")
        self.position = position
        return position

    def read(self, size: int = -1) -> bytes:
        size = self.length - self.position if size < 0 else min(size, self.length - self.position)
        if size > MAX_DOCUMENT_BYTES:
            raise ValueError("Refusing an unbounded remote SDK read")
        if size == 0:
            return b""
        data, _ = self._range(self.position, self.position + size - 1)
        self.position += len(data)
        return data


def prepare_license_evidence(cache_root: Path, allow_download: bool = False,
                             seed_root: Path | None = None) -> dict:
    """Populate an ignored public evidence cache through an explicit pipeline step.

    seed_root may point to the prior native-symbols-v26-followup audit directory.
    Every imported/downloaded file is checked against the committed registry hash.
    Existing differing evidence is never replaced. Missing files are reported.
    """
    result: dict = {"available": [], "missing": [], "downloaded": []}
    archives: dict[str, zipfile.ZipFile] = {}
    try:
        for record in load_registry()["external_evidence"]:
            relative = record["cache_path"]
            target = _inside(Path(cache_root), relative)
            data = target.read_bytes() if target.is_file() else None
            seed = _inside(Path(seed_root), record["seed_path"]) if seed_root else None
            if data is None and seed is not None and seed.is_file():
                data = seed.read_bytes()
            if data is None and allow_download:
                url = record["url"]
                if not url.startswith("https://"):
                    raise ValueError("Evidence downloads must use the committed HTTPS source")
                if record.get("member"):
                    if url not in archives:
                        archives[url] = zipfile.ZipFile(_RemoteZip(url))
                    info = archives[url].getinfo(record["member"])
                    if info.file_size > MAX_DOCUMENT_BYTES:
                        raise ValueError("Oversized upstream license document")
                    data = archives[url].read(info)
                else:
                    with urllib.request.urlopen(url, timeout=30) as response:
                        data = response.read(MAX_DOCUMENT_BYTES + 1)
                    if record.get("encoding") == "base64":
                        data = base64.b64decode(data, validate=True)
                result["downloaded"].append(relative)
            if data is None:
                result["missing"].append(relative)
                continue
            if _digest(data) != record["sha256"]:
                raise ValueError("Public evidence checksum mismatch: " + relative)
            _put(Path(cache_root), relative, data)
            result["available"].append(relative)
    finally:
        for archive in archives.values():
            archive.close()
    return result
