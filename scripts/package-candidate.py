#!/usr/bin/env python3
"""Create a local candidate archive; never build, deploy, start services or contact a network."""

import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import sys
import tarfile
import zipfile
import zlib


VERSION = "0.1.0-SNAPSHOT"
ARCHIVE_NAME = f"ai-reviewer-{VERSION}-candidate.tar.gz"
MAX_WAR_BYTES = 256 * 1024 * 1024
MAX_TEXT_BYTES = 2 * 1024 * 1024
MAX_ZIP_ENTRIES = 100_000
MAX_MANIFEST_BYTES = 64 * 1024
MAX_COMPRESSED_MANIFEST_BYTES = 128 * 1024
ALLOWLIST = (
    "deploy/linux/ai-reviewer.service",
    "deploy/linux/nginx.conf.example",
    "deploy/linux/reviewer.env.example",
    "deploy/linux/verify-artifact.sh",
    "docs/AI-EVALUATION.md",
    "docs/CANDIDATE-PACKAGE.md",
    "docs/DEPENDENCY-AUDIT.md",
    "docs/LINUX-DEPLOYMENT.md",
    "docs/LOAD-VALIDATION.md",
    "docs/MONITORING.md",
    "docs/OPENAI-INTEGRATION.md",
    "docs/OPERATIONS.md",
    "docs/PUBLIC-GIT-VALIDATION.md",
    "docs/DB-RECOVERY-VALIDATION.md",
    "docs/QUEUE-VALIDATION.md",
    "docs/RELEASE-CHECKLIST.md",
    "docs/SECRET-HYGIENE.md",
    "docs/USER-EXPERIENCE.md",
    "docs/WORKER-CONCURRENCY-VALIDATION.md",
    "docs/WSL-VALIDATION.md",
)
# Exact unfilled template defaults. Configuration changes require an explicit policy update.
ENVIRONMENT_DEFAULTS = {
    "DB_URL": "jdbc:postgresql://127.0.0.1:5432/ai_reviewer",
    "DB_USERNAME": "ai_reviewer", "DB_PASSWORD": "",
    "BOOTSTRAP_ADMIN_USERNAME": "", "BOOTSTRAP_ADMIN_PASSWORD": "", "BOOTSTRAP_ADMIN_GIT_USERNAME": "",
    "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": "8080", "SESSION_COOKIE_SECURE": "true",
    "SERVER_SHUTDOWN": "graceful", "SPRING_LIFECYCLE_TIMEOUTPERSHUTDOWNPHASE": "120s",
    "SERVER_FORWARDHEADERSSTRATEGY": "NATIVE", "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES": "127[.]0[.]0[.]1",
    "SERVER_TOMCAT_REMOTEIP_REMOTEIPHEADER": "X-Forwarded-For", "SERVER_TOMCAT_REMOTEIP_PROTOCOLHEADER": "X-Forwarded-Proto",
    "SERVER_TOMCAT_REDIRECTCONTEXTROOT": "false", "GIT_ALLOWED_HOSTS": "github.com",
    "GIT_TOKEN": "", "GIT_TOKEN_HOST": "github.com", "AI_PROVIDER": "ollama",
    "AI_BASE_URL": "http://127.0.0.1:11434", "AI_MODEL": "gemma3:1b", "AI_API_KEY": "",
    "OPENAI_MODEL": "", "OPENAI_API_KEY": "", "AI_TIMEOUT_SECONDS": "120", "AI_CONTEXT_TOKENS": "32768",
    "AI_MAX_OUTPUT_TOKENS": "4096", "AI_MAX_REVIEW_CALLS": "8", "REVIEW_ENABLED": "false",
    "REVIEW_WORKER_ENABLED": "false", "REVIEW_CRON": '"0 0 * * * *"', "REVIEW_MAX_COMMITS": "100",
    "REVIEW_CONCURRENCY": "2", "OPERATIONS_STALE_AFTER_MINUTES": "120",
    "JDBC_QUERY_TIMEOUT_SECONDS": "30", "JDBC_SOCKET_TIMEOUT_SECONDS": "45", "JDBC_CONNECT_TIMEOUT_SECONDS": "10",
}
SECRET_PATTERNS = (
    re.compile(rb"(?<![A-Za-z0-9_])sk-(?:(?:proj-|svcacct-)[A-Za-z0-9_-]{32,}|(?!proj-|svcacct-)[A-Za-z0-9_-]{32,})"),
    re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY(?: BLOCK)?-----"),
)
REQUIRED_WAR_ENTRIES = (
    "META-INF/MANIFEST.MF",
    "org/springframework/boot/loader/launch/WarLauncher.class",
    "WEB-INF/classes/com/aicreviewer/AiCodeReviewerApplication.class",
    "WEB-INF/jsp/login.jsp",
)


class PackageError(Exception):
    """Only fixed categories, never caller paths, file contents or underlying exception text."""


def reject_link(metadata):
    attributes = getattr(metadata, "st_file_attributes", None)
    if os.name == "nt" and attributes is None:
        raise PackageError("file-metadata-unavailable")
    if stat.S_ISLNK(metadata.st_mode) or (attributes is not None and attributes & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)):
        raise PackageError("links-and-reparse-points-forbidden")


def checked_path(value, *, kind):
    path = Path(value)
    if (not path.is_absolute() or ".." in path.parts or str(path).startswith(("\\\\", "//"))):
        raise PackageError("absolute-local-path-required")
    current = Path(path.anchor)
    reject_link(current.lstat())
    for index, part in enumerate(path.parts[1:]):
        current = current / part
        final = index == len(path.parts) - 2
        try:
            metadata = current.lstat()
        except FileNotFoundError:
            if final and kind == "absent":
                return path, None
            raise PackageError("required-input-or-parent-missing") from None
        reject_link(metadata)
        if not final and not stat.S_ISDIR(metadata.st_mode):
            raise PackageError("parent-is-not-directory")
    if kind == "absent":
        raise PackageError("output-already-exists")
    metadata = path.lstat()
    if kind == "file" and not stat.S_ISREG(metadata.st_mode):
        raise PackageError("regular-input-file-required")
    if kind == "directory" and not stat.S_ISDIR(metadata.st_mode):
        raise PackageError("directory-required")
    return path, metadata


def identity(metadata):
    return metadata.st_dev, metadata.st_ino


def read_regular(path, limit):
    path, before = checked_path(path, kind="file")
    if before.st_size > limit:
        raise PackageError("input-size-limit")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, "rb") as stream:
        opened = os.fstat(stream.fileno())
        reject_link(opened)
        if not stat.S_ISREG(opened.st_mode) or identity(opened) != identity(before):
            raise PackageError("input-changed-during-read")
        data = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
    _, current = checked_path(path, kind="file")
    if (len(data) > limit or len(data) != before.st_size or identity(current) != identity(before)
            or after.st_mtime_ns != before.st_mtime_ns or after.st_size != before.st_size):
        raise PackageError("input-changed-or-size-limit")
    return data


def normalized_text(data):
    if any(pattern.search(data) for pattern in SECRET_PATTERNS):
        raise PackageError("possible-secret-in-allowed-text")
    try:
        value = data.decode("utf-8")
    except UnicodeError:
        raise PackageError("allowed-text-must-be-utf8") from None
    if "\x00" in value:
        raise PackageError("invalid-text-content")
    return value.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")


def validate_environment(data):
    values = {}
    for line in data.decode("utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in values:
            raise PackageError("environment-template-must-be-unfilled-defaults")
        values[key] = value
    if values != ENVIRONMENT_DEFAULTS:
        raise PackageError("environment-template-must-be-unfilled-defaults")


def bounded_manifest(war, info, data):
    if info.file_size > MAX_MANIFEST_BYTES:
        raise PackageError("war-manifest-size-limit")
    if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
        raise PackageError("unsupported-war-manifest-compression")
    if info.compress_size > MAX_COMPRESSED_MANIFEST_BYTES:
        raise PackageError("war-manifest-compressed-size-limit")
    # ZipFile.open verifies the local name, overlap and encryption without inflating data.
    with war.open(info):
        pass
    header = struct.unpack("<4s5H3I2H", data[info.header_offset:info.header_offset + 30])
    if header[0] != b"PK\x03\x04" or header[2] != info.flag_bits or header[3] != info.compress_type:
        raise PackageError("invalid-war-manifest-header")
    start = info.header_offset + 30 + header[9] + header[10]
    end = start + info.compress_size
    if start < 0 or end > len(data):
        raise PackageError("invalid-war-manifest-range")
    compressed = data[start:end]
    if info.compress_type == zipfile.ZIP_STORED:
        result = compressed
    else:
        inflater = zlib.decompressobj(-zlib.MAX_WBITS)
        # Bound the real DEFLATE output independently of attacker-controlled file_size.
        # Never call flush(): it can allocate additional unbounded output.
        result = inflater.decompress(compressed, MAX_MANIFEST_BYTES + 1)
        if len(result) > MAX_MANIFEST_BYTES:
            raise PackageError("war-manifest-size-limit")
        if not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
            raise PackageError("invalid-war-manifest-stream")
    if len(result) > MAX_MANIFEST_BYTES:
        raise PackageError("war-manifest-size-limit")
    if len(result) != info.file_size or zlib.crc32(result) != info.CRC:
        raise PackageError("invalid-war-manifest-integrity")
    return result


def validate_war(data):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as war:
            entries = war.infolist()
            if len(entries) > MAX_ZIP_ENTRIES:
                raise PackageError("war-entry-count-limit")
            names = set()
            for entry in entries:
                name = entry.orig_filename
                parts = PurePosixPath(name).parts
                if (not parts or name.startswith("/") or ".." in parts or "\\" in name or ":" in name
                        or "\x00" in name or name in names or stat.S_ISLNK(entry.external_attr >> 16)):
                    raise PackageError("unsafe-or-duplicate-war-entry")
                names.add(name)
            if not set(REQUIRED_WAR_ENTRIES).issubset(names):
                raise PackageError("required-war-content-missing")
            if any(war.getinfo(name).is_dir() or stat.S_ISDIR(war.getinfo(name).external_attr >> 16)
                   for name in REQUIRED_WAR_ENTRIES):
                raise PackageError("required-war-content-is-not-file")
            manifest_info = war.getinfo("META-INF/MANIFEST.MF")
            manifest = bounded_manifest(war, manifest_info, data).decode("utf-8").replace("\r\n", "\n")
    except (zipfile.BadZipFile, UnicodeError, RuntimeError, NotImplementedError, zlib.error, EOFError, struct.error):
        raise PackageError("invalid-war-archive") from None
    unfolded = []
    for line in manifest.split("\n"):
        if not line:
            break  # Main attributes only; named-entry sections do not define the launcher.
        if line.startswith(" "):
            if not unfolded:
                raise PackageError("invalid-war-manifest")
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    attributes = {}
    for line in unfolded:
        key, separator, value = line.partition(": ")
        key = key.lower()
        if not separator or key in attributes:
            raise PackageError("invalid-war-manifest")
        attributes[key] = value
    for key, value in {
        "main-class": "org.springframework.boot.loader.launch.WarLauncher",
        "start-class": "com.aicreviewer.AiCodeReviewerApplication",
        "spring-boot-version": "4.0.8",
        "implementation-version": VERSION,
    }.items():
        if attributes.get(key) != value:
            raise PackageError("war-manifest-contract-mismatch")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def payload(repository, war, expected, revision, epoch):
    checked_path(repository, kind="directory")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise PackageError("expected-war-sha256-must-be-64-hex")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", revision):
        raise PackageError("source-revision-must-be-40-hex")
    if not isinstance(epoch, int) or isinstance(epoch, bool) or not 0 <= epoch <= 0xffffffff:
        raise PackageError("source-date-epoch-outside-gzip-range")
    war_bytes = read_regular(war, MAX_WAR_BYTES)
    if digest(war_bytes) != expected.lower():
        raise PackageError("war-sha256-mismatch")
    validate_war(war_bytes)
    files = {"ai-code-reviewer.war": (war_bytes, 0o644)}
    for name in ALLOWLIST:
        data = normalized_text(read_regular(repository / name, MAX_TEXT_BYTES))
        if name == "deploy/linux/reviewer.env.example":
            validate_environment(data)
        files[name] = (data, 0o755 if name.endswith(".sh") else 0o644)
    metadata = {
        "schemaVersion": 1,
        "packageKind": "candidate",
        "releaseApproved": False,
        "version": VERSION,
        "sourceRevision": revision.lower(),
        "sourceRevisionAttestation": "operator-provided; not verified against WAR",
        "sourceDateEpoch": epoch,
        "warSHA256": expected.lower(),
        "documentLinkScope": "Bundled docs and deployment paths are preserved; links to source, tests, CI configuration or other scripts require the source checkout.",
        "reproducibilityScope": "Identical input WAR bytes, normalized allowed text, source claim and epoch with the same Python/zlib runtime; not a reproducible source build or release approval.",
        "secretCheckScope": "Unfilled environment defaults and limited text key patterns; not a guarantee that the WAR or arbitrary text contains no secrets.",
        "files": [{"path": name, "size": len(data), "sha256": digest(data), "mode": format(mode, "04o")}
                  for name, (data, mode) in sorted(files.items())],
    }
    files["CANDIDATE.json"] = ((json.dumps(metadata, ensure_ascii=True, sort_keys=True, indent=2) + "\n").encode("utf-8"), 0o644)
    sums = "".join(f"{digest(data)}  {name}\n" for name, (data, _) in sorted(files.items()))
    files["SHA256SUMS"] = (sums.encode("ascii"), 0o644)
    return files


def write_archive(stream, files, epoch):
    with gzip.GzipFile(filename="", mode="wb", fileobj=stream, mtime=epoch, compresslevel=9) as compressed:
        with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.USTAR_FORMAT) as archive:
            for name, (data, mode) in sorted(files.items()):
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                entry.mode = mode
                entry.uid = entry.gid = 0
                entry.uname = entry.gname = ""
                entry.mtime = epoch
                archive.addfile(entry, io.BytesIO(data))


def remove_owned_output(directory, owned_identity, created_files):
    _, current = checked_path(directory, kind="directory")
    if identity(current) != owned_identity:
        raise PackageError("output-identity-changed-cleanup-refused")
    # No recursive deletion. Only files successfully created exclusively by this call are eligible.
    for name, file_identity in created_files.items():
        if name not in (ARCHIVE_NAME, ARCHIVE_NAME + ".sha256"):
            raise PackageError("unexpected-output-cleanup-refused")
        target = directory / name
        try:
            _, metadata = checked_path(target, kind="file")
        except PackageError as error:
            if str(error) == "required-input-or-parent-missing":
                continue
            raise
        if not stat.S_ISREG(metadata.st_mode) or identity(metadata) != file_identity:
            raise PackageError("unexpected-output-cleanup-refused")
        target.unlink()
    directory.rmdir()  # Unexpected new entries are preserved and cause cleanup to fail.


def package_candidate(*, repository, war, expected, revision, output, epoch=0):
    repository = Path(repository)
    output, _ = checked_path(output, kind="absent")
    # Validate every input before creating any output directory.
    files = payload(repository, war, expected, revision, epoch)
    checked_path(output, kind="absent")
    output.mkdir(mode=0o700)  # Exclusive; a concurrent creator must never be overwritten.
    _, created = checked_path(output, kind="directory")
    owned_identity = identity(created)
    created_files = {}
    try:
        archive_path = output / ARCHIVE_NAME
        with archive_path.open("xb") as stream:
            created_files[ARCHIVE_NAME] = identity(os.fstat(stream.fileno()))
            write_archive(stream, files, epoch)
        archive_digest = digest(read_regular(archive_path, MAX_WAR_BYTES + len(ALLOWLIST) * MAX_TEXT_BYTES + 1024 * 1024))
        _, current = checked_path(output, kind="directory")
        if identity(current) != owned_identity:
            raise PackageError("output-identity-changed")
        with (output / (ARCHIVE_NAME + ".sha256")).open("x", encoding="ascii", newline="\n") as sidecar:
            created_files[ARCHIVE_NAME + ".sha256"] = identity(os.fstat(sidecar.fileno()))
            sidecar.write(f"{archive_digest}  {ARCHIVE_NAME}\n")
        return archive_digest
    except BaseException:
        remove_owned_output(output, owned_identity, created_files)
        raise


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        self.print_usage(sys.stderr)
        self.exit(2, "Invalid command arguments; use --help.\n")


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("--war", required=True, help="Absolute path to the already verified executable WAR")
    parser.add_argument("--expected-war-sha256", required=True, help="Independent approved build record's 64-hex SHA-256")
    parser.add_argument("--source-revision", required=True, help="Operator-provided 40-hex claim; not checked against the WAR")
    parser.add_argument("--output-dir", required=True, help="Absolute new directory; its parent must already exist")
    parser.add_argument("--source-date-epoch", type=int, default=0, help="Fixed gzip/tar timestamp, 0..4294967295 (default: 0)")
    args = parser.parse_args(argv)
    try:
        package_candidate(repository=Path(__file__).absolute().parent.parent, war=Path(args.war),
                          expected=args.expected_war_sha256, revision=args.source_revision,
                          output=Path(args.output_dir), epoch=args.source_date_epoch)
    except PackageError as error:
        print(f"Candidate packaging failed: {error}", file=sys.stderr)
        return 1
    except (OSError, ValueError, OverflowError, tarfile.TarError, zipfile.BadZipFile, zlib.error, EOFError, struct.error):
        print("Candidate packaging failed: file-or-archive-operation-error", file=sys.stderr)
        return 1
    print("Candidate package created locally. No build provenance, deployment or release approval is asserted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
