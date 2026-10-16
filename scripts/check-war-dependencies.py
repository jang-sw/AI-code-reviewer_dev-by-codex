#!/usr/bin/env python3
"""Check local WAR dependency coverage before any public vulnerability query.

No network, Maven invocation, extraction or artifact execution occurs here.
The expected WAR hash must come from the caller's verified build record.
"""

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib


_spec = importlib.util.spec_from_file_location(
    "war_audit_package", Path(__file__).with_name("package-candidate.py"))
_package = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_package)
TOKEN = re.compile(r"^[A-Za-z0-9_+.-]+$")
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
MAX_JAR = 64 * 1024 * 1024
MAX_TOTAL_JARS = 256 * 1024 * 1024
MAX_METADATA = 64 * 1024
MAX_RECORDS = 4096


class AuditError(Exception):
    """Messages are fixed categories, never input names, values or paths."""


def require(value, category):
    if not value:
        raise AuditError(category)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read(path, limit):
    return _package.read_regular(Path(path), limit)


def valid_token(value):
    return (isinstance(value, str) and len(value) <= 256
            and TOKEN.fullmatch(value) is not None and value not in (".", ".."))


def gav(value):
    parts = value.split(":") if isinstance(value, str) else []
    require(len(parts) == 3 and all(valid_token(part) for part in parts), "invalid-coordinate")
    require(all(part not in ("", ".", "..") for part in parts[0].split(".")), "invalid-group-path")
    return tuple(parts)


def approved_coordinates(data):
    approved = set()
    for line in data.decode("utf-8-sig").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            gav(line)
            require(line not in approved, "duplicate-approved-coordinate")
            approved.add(line)
    require(approved, "empty-approved-coordinates")
    return approved


def parse_inventory(data, approved):
    records, seen = [], set()
    for line in data.decode("utf-8-sig").splitlines():
        line = re.sub(r"^\[INFO\]\s*", "", line).strip()
        if not line or line == "The following files have been resolved:":
            continue
        parts = re.split(r"\s+--\s+module\s+", line, maxsplit=1)[0].split(":")
        require(len(parts) in (5, 6) and all(valid_token(part) for part in parts), "invalid-inventory-line")
        if parts[-1] == "test":
            continue
        require(parts[-1] in ("compile", "runtime", "provided"), "unsupported-inventory-scope")
        coordinate = ":".join((parts[0], parts[1], parts[-2]))
        gav(coordinate)
        require(coordinate in approved, "unapproved-inventory-coordinate")
        group, artifact, packaging = parts[:3]
        classifier = parts[3] if len(parts) == 6 else ""
        version, scope = parts[-2:]
        key = (group, artifact, packaging, classifier, version, scope)
        if key not in seen:
            seen.add(key)
            records.append({"group": group, "artifact": artifact, "packaging": packaging,
                            "classifier": classifier, "version": version, "scope": scope,
                            "coordinate": coordinate})
        require(len(records) <= MAX_RECORDS, "inventory-count-limit")
    require(records, "empty-production-inventory")
    return records


def unique_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate-policy-json-key")
            result[key] = value
        return result
    return json.loads(data.decode("utf-8-sig"), object_pairs_hook=pairs)


def supplements(data, approved):
    policy = unique_json(data)
    require(isinstance(policy, dict) and set(policy) == {"schemaVersion", "artifacts"}
            and type(policy["schemaVersion"]) is int and policy["schemaVersion"] == 1
            and isinstance(policy["artifacts"], list) and len(policy["artifacts"]) <= MAX_RECORDS,
            "invalid-supplemental-policy")
    entries, coordinates = {}, set()
    for item in policy["artifacts"]:
        require(isinstance(item, dict) and set(item) == {"coordinate", "entry", "size", "sha256", "provenance"},
                "invalid-supplemental-artifact")
        group, artifact, version = gav(item["coordinate"])
        require(item["coordinate"] in approved, "unapproved-supplemental-coordinate")
        require(item["entry"] in (f"WEB-INF/lib/{artifact}-{version}.jar",
                                  f"WEB-INF/lib-provided/{artifact}-{version}.jar"),
                "invalid-supplemental-entry")
        require(type(item["size"]) is int and 0 < item["size"] <= MAX_JAR
                and isinstance(item["sha256"], str) and SHA256.fullmatch(item["sha256"]),
                "invalid-supplemental-integrity")
        provenance = (f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/"
                      f"{artifact}/{version}/{artifact}-{version}.pom")
        require(item["provenance"] == provenance, "invalid-public-provenance")
        require(item["entry"].casefold() not in entries and item["coordinate"] not in coordinates,
                "duplicate-supplemental-artifact")
        entries[item["entry"].casefold()] = item
        coordinates.add(item["coordinate"])
    return entries


def zip_entries(archive, *, war):
    entries = archive.infolist()
    require(len(entries) <= (100_000 if war else 50_000), "zip-entry-count-limit")
    names, aliases = set(), set()
    for entry in entries:
        name = entry.orig_filename
        path = PurePosixPath(name)
        require(name and not name.startswith("/") and ".." not in path.parts and "\\" not in name
                and ":" not in name and "\x00" not in name and str(path) == name.rstrip("/")
                and name not in names and not stat.S_ISLNK(entry.external_attr >> 16), "unsafe-or-duplicate-zip-entry")
        alias = name.rstrip("/").casefold()
        require(not war or alias not in aliases, "case-aliased-war-entry")
        names.add(name)
        aliases.add(alias)
    return entries


def member_bytes(archive, raw, info, limit):
    """Bound real inflate output, including forged ZIP size/CRC metadata."""
    require(0 <= info.file_size <= limit and 0 <= info.compress_size <= limit,
            "zip-member-size-limit")
    require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "unsupported-zip-compression")
    with archive.open(info):
        pass  # Validate local name, overlap, unsupported flags and encryption first.
    offset = info.header_offset
    require(0 <= offset and offset + 30 <= len(raw), "invalid-zip-header-range")
    header = struct.unpack("<4s5H3I2H", raw[offset:offset + 30])
    require(header[0] == b"PK\x03\x04" and header[2] == info.flag_bits
            and header[3] == info.compress_type, "invalid-zip-header")
    start = offset + 30 + header[9] + header[10]
    end = start + info.compress_size
    require(0 <= start <= end <= len(raw), "invalid-zip-member-range")
    compressed = raw[start:end]
    if info.compress_type == zipfile.ZIP_STORED:
        result = compressed
    else:
        inflater = zlib.decompressobj(-zlib.MAX_WBITS)
        result = inflater.decompress(compressed, limit + 1)
        require(len(result) <= limit and inflater.eof and not inflater.unused_data
                and not inflater.unconsumed_tail, "zip-inflate-size-or-stream-limit")
    require(len(result) <= limit and len(result) == info.file_size
            and zlib.crc32(result) == info.CRC, "zip-member-integrity-mismatch")
    return result


def jar_properties(data):
    coordinates = set()
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for info in zip_entries(jar, war=False):
            if not re.fullmatch(r"META-INF/maven/[^/]+/[^/]+/pom\.properties", info.filename):
                continue
            properties = {}
            text = member_bytes(jar, data, info, MAX_METADATA).decode("utf-8-sig")
            for line in text.splitlines():
                line = line.strip()
                if not line or line.startswith(("#", "!")):
                    continue
                key, separator, value = line.partition("=")
                require(separator and key.strip() not in properties, "invalid-maven-properties")
                properties[key.strip()] = value.strip()
            require(all(name in properties for name in ("groupId", "artifactId", "version")),
                    "incomplete-maven-properties")
            coordinate = ":".join(properties[name] for name in ("groupId", "artifactId", "version"))
            gav(coordinate)
            coordinates.add(coordinate)
    return coordinates


def application_pom(data):
    # This project builds UTF-8 POMs. Decode first so UTF-16/NUL-encoded
    # declarations cannot bypass the declaration guard before XML parsing.
    text = data.decode("utf-8-sig")
    require("\x00" not in text and "<!DOCTYPE" not in text.upper()
            and "<!ENTITY" not in text.upper(), "unsafe-application-pom")
    root = ET.fromstring(text)
    namespace = "{http://maven.apache.org/POM/4.0.0}" if root.tag.startswith("{") else ""
    group = root.findtext(namespace + "groupId") or root.findtext(namespace + "parent/" + namespace + "groupId")
    artifact = root.findtext(namespace + "artifactId")
    require(valid_token(group) and valid_token(artifact), "invalid-application-pom")
    gav(f"{group}:{artifact}:unused")
    return f"META-INF/maven/{group}/{artifact}/pom.xml"


def _audit_war(*, war_file, expected_war_sha256, inventory_file, pom_file,
               approved_file, supplemental_file, maven_repository):
    require(isinstance(expected_war_sha256, str) and SHA256.fullmatch(expected_war_sha256), "expected-war-sha256-required")
    war_data = read(war_file, _package.MAX_WAR_BYTES)
    require(digest(war_data) == expected_war_sha256.lower(), "war-sha256-mismatch")
    _package.validate_war(war_data)
    inventory_data = read(inventory_file, 2 * 1024 * 1024)
    approved_data = read(approved_file, 2 * 1024 * 1024)
    policy_data = read(supplemental_file, 2 * 1024 * 1024)
    pom_data = read(pom_file, MAX_METADATA)
    approved = approved_coordinates(approved_data)
    records = parse_inventory(inventory_data, approved)
    policy = supplements(policy_data, approved)
    repository, _ = _package.checked_path(maven_repository, kind="directory")
    by_filename, packages = {}, {}
    for record in records:
        suffix = "-" + record["classifier"] if record["classifier"] else ""
        filename = f"{record['artifact']}-{record['version']}{suffix}.{record['packaging']}"
        by_filename.setdefault(filename, []).append(record)
        packages.setdefault(record["coordinate"], {"name": f"{record['group']}:{record['artifact']}",
                                                   "version": record["version"], "scope": record["scope"]})
    matched, used, basenames = [], set(), set()
    ordinary = supplemental = total = 0
    with zipfile.ZipFile(io.BytesIO(war_data)) as archive:
        entries = zip_entries(archive, war=True)
        pom_name = application_pom(pom_data)
        require(pom_name in archive.namelist() and member_bytes(
            archive, war_data, archive.getinfo(pom_name), MAX_METADATA) == pom_data, "embedded-application-pom-mismatch")
        for info in entries:
            root = next((value for value in ("WEB-INF/lib", "WEB-INF/lib-provided")
                         if info.filename.casefold().startswith(value.casefold() + "/")), None)
            if root is None or info.is_dir():
                continue
            require(re.fullmatch(re.escape(root) + r"/[^/]+\.jar", info.filename), "unexpected-library-entry")
            filename = PurePosixPath(info.filename).name
            require(filename.casefold() not in basenames, "duplicate-library-basename")
            basenames.add(filename.casefold())
            require(len(basenames) <= MAX_RECORDS, "library-count-limit")
            jar_data = member_bytes(archive, war_data, info, MAX_JAR)
            total += len(jar_data)
            require(total <= MAX_TOTAL_JARS, "total-library-size-limit")
            metadata = jar_properties(jar_data)
            candidates = by_filename.get(filename, [])
            identities = {(r["group"], r["artifact"], r["packaging"], r["classifier"], r["version"]) for r in candidates}
            require(len(identities) <= 1, "ambiguous-library-coordinate")
            if candidates:
                record = candidates[0]
                coordinate = record["coordinate"]
                suffix = "-" + record["classifier"] if record["classifier"] else ""
                cached = repository.joinpath(*record["group"].split("."), record["artifact"],
                                             record["version"], f"{record['artifact']}-{record['version']}{suffix}.jar")
                require(read(cached, MAX_JAR) == jar_data, "maven-cache-bytes-mismatch")
                binding = "maven-cache-bytes"
                ordinary += 1
            else:
                item = policy.get(info.filename.casefold())
                require(item is not None and item["entry"] == info.filename, "unreviewed-library")
                require(len(jar_data) == item["size"] and digest(jar_data) == item["sha256"].lower(),
                        "supplemental-library-integrity-mismatch")
                coordinate = item["coordinate"]
                group, artifact, version = gav(coordinate)
                packages.setdefault(coordinate, {"name": f"{group}:{artifact}", "version": version, "scope": "runtime"})
                binding = "reviewed-supplement-sha256"
                supplemental += 1
            require(not metadata or metadata == {coordinate}, "nested-maven-coordinate-mismatch")
            used.add(coordinate)
            matched.append({"entry": info.filename, "coordinate": coordinate, "sha256": digest(jar_data), "binding": binding})
    require(matched, "empty-war-libraries")
    inventory_coordinates = {r["coordinate"] for r in records}
    inventory_only = sorted(inventory_coordinates - used)
    return {
        "status": "COMPLETE", "networkUsed": False,
        "warSha256": digest(war_data), "inventorySha256": digest(inventory_data),
        "pomSha256": digest(pom_data), "allowlistSha256": digest(approved_data),
        "supplementalPolicySha256": digest(policy_data), "packages": list(packages.values()),
        "coverage": {"nestedJarCount": len(matched), "ordinaryJarCount": ordinary,
                     "supplementalJarCount": supplemental, "inventoryOnlyCount": len(inventory_only),
                     "inventoryOnlyCoordinates": inventory_only, "matched": matched,
                     "bindingScope": "Local Maven cache byte equality and explicitly reviewed supplemental digests; no signature, source-build or supply-chain attestation."},
    }


def audit_war(*, war_file, expected_war_sha256, inventory_file, pom_file,
              approved_file, supplemental_file, maven_repository):
    try:
        return _audit_war(war_file=war_file, expected_war_sha256=expected_war_sha256,
                          inventory_file=inventory_file, pom_file=pom_file,
                          approved_file=approved_file, supplemental_file=supplemental_file,
                          maven_repository=maven_repository)
    except AuditError:
        raise
    except _package.PackageError as error:
        raise AuditError(str(error)) from None
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, zlib.error,
            RuntimeError, NotImplementedError, EOFError, struct.error, ET.ParseError):
        raise AuditError("invalid-local-audit-input") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("war-file", "expected-war-sha256", "inventory-file", "pom-file", "approved-file",
                 "supplemental-file", "maven-repository"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--report-file")
    args = parser.parse_args(argv)
    report_path = Path(args.report_file).absolute() if args.report_file else None
    try:
        if report_path is not None:
            _package.checked_path(report_path, kind="absent")
        inputs = {key: Path(value).absolute() if key != "expected_war_sha256" else value
                  for key, value in vars(args).items() if key != "report_file"}
        result = audit_war(**inputs)
        exit_code = 0
    except (AuditError, _package.PackageError) as error:
        result, exit_code = {"status": "INCOMPLETE", "error": str(error), "networkUsed": False}, 1
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, zlib.error,
            RuntimeError, NotImplementedError, EOFError, struct.error, ET.ParseError):
        result, exit_code = {"status": "INCOMPLETE", "error": "invalid-local-audit-input", "networkUsed": False}, 1
    payload = (json.dumps(result, ensure_ascii=True, indent=2) + "\n").encode("utf-8")
    if report_path is not None:
        try:
            _package.checked_path(report_path, kind="absent")
            with report_path.open("xb") as output:
                output.write(payload)
        except (OSError, _package.PackageError):
            result, exit_code = {"status": "INCOMPLETE", "error": "report-output-unavailable", "networkUsed": False}, 1
    print(json.dumps(result, ensure_ascii=True))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
