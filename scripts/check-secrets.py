#!/usr/bin/env python3
"""Inspect tracked working files or the complete Git index without revealing matches."""

import argparse
import json
import ntpath
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys


MAX_FILE_BYTES = 16 * 1024 * 1024
PATTERNS = (
    ("openai-api-key", re.compile(
        rb"(?<![A-Za-z0-9_])sk-(?:(?:proj-|svcacct-)[A-Za-z0-9_-]{32,}|(?!proj-|svcacct-)[A-Za-z0-9_-]{32,})"
    )),
    ("private-key-header", re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY(?: BLOCK)?-----")),
)


class ScanError(Exception):
    def __init__(self, category, path="."):
        super().__init__(category)
        self.category = category
        self.path = path


def git(repo, *args):
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), *args], stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, check=False, timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ScanError("git-read-error") from None
    if result.returncode:
        raise ScanError("git-read-error")
    return result.stdout


def entries(repo):
    result = []
    for raw in git(repo, "ls-files", "--stage", "-z").split(b"\0"):
        if not raw:
            continue
        try:
            metadata, raw_path = raw.split(b"\t", 1)
            mode, oid, stage = metadata.decode("ascii").split(" ")
            path = os.fsdecode(raw_path)
        except (ValueError, UnicodeError):
            raise ScanError("invalid-index-entry") from None
        if stage != "0":
            raise ScanError("unmerged-index", path)
        if mode not in ("100644", "100755"):
            raise ScanError("unsupported-file-mode", path)
        if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", oid) or set(oid) == {"0"}:
            raise ScanError("invalid-index-object", path)
        parts = PurePosixPath(path).parts
        if (not parts or PurePosixPath(path).is_absolute() or ".." in parts
                or "\\" in path or ntpath.splitdrive(path)[0]):
            raise ScanError("invalid-index-path", path)
        result.append((path, oid))
    return result


def is_environment_file(path):
    name = PurePosixPath(path).name.casefold()
    return name != ".env.example" and (name == ".env" or name.startswith(".env."))


def reject_file_link(metadata, path):
    # Windows lstat exposes reparse attributes even on Python versions without Path.is_junction.
    # Reject all reparse points, including junctions, before opening any descendant or file.
    attributes = getattr(metadata, "st_file_attributes", None)
    if os.name == "nt" and attributes is None:
        raise ScanError("file-metadata-error", path)
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    if stat.S_ISLNK(metadata.st_mode) or (attributes is not None and attributes & reparse_flag):
        raise ScanError("unsupported-file-link", path)


def working_content(repo, path):
    target = repo
    try:
        for part in PurePosixPath(path).parts:
            target = target / part
            metadata = target.lstat()
            reject_file_link(metadata, path)
        if not stat.S_ISREG(metadata.st_mode):
            raise ScanError("unsupported-file-mode", path)
        with target.open("rb") as stream:
            data = stream.read(MAX_FILE_BYTES + 1)
    except FileNotFoundError:
        # A tracked working file may have been deleted; --staged still inspects its index blob.
        return None
    except OSError:
        raise ScanError("file-read-error", path) from None
    if len(data) > MAX_FILE_BYTES:
        raise ScanError("file-size-limit", path)
    return data


def staged_content(repo, path, oid):
    try:
        size = int(git(repo, "cat-file", "-s", oid))
    except ValueError:
        raise ScanError("invalid-object-size", path) from None
    if size < 0 or size > MAX_FILE_BYTES:
        raise ScanError("file-size-limit", path)
    data = git(repo, "cat-file", "blob", oid)
    if len(data) != size:
        raise ScanError("object-read-error", path)
    return data


def finding(path, line, category):
    # JSON quotes escape line breaks/control characters in a Git filename, never matched contents.
    print(f"{json.dumps(path, ensure_ascii=True)}:{line}:{category}")


def scan(repo, staged):
    root_bytes = git(repo, "rev-parse", "--show-toplevel").rstrip(b"\r\n")
    root = Path(os.fsdecode(root_bytes))
    found = False
    incomplete = False
    for path, oid in entries(root):
        if is_environment_file(path):
            finding(path, 1, "tracked-environment-file")
            found = True
            continue  # Filename alone is forbidden; never read the environment file's values.
        try:
            data = staged_content(root, path, oid) if staged else working_content(root, path)
        except ScanError as error:
            finding(error.path, 0, error.category)
            incomplete = True
            continue
        if data is None:
            continue
        reported = set()
        for category, pattern in PATTERNS:
            line = 1
            cursor = 0
            for match in pattern.finditer(data):
                line += data.count(b"\n", cursor, match.start())
                cursor = match.start()
                if (line, category) not in reported:
                    finding(path, line, category)
                    reported.add((line, category))
                    found = True
    return 2 if incomplete else (1 if found else 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--tracked", action="store_true", help="Inspect existing Git-tracked working files")
    mode.add_argument("--staged", action="store_true", help="Inspect every file in the Git index")
    parser.add_argument("--repo", type=Path, default=Path.cwd(), help="Git working tree (default: current directory)")
    args = parser.parse_args()
    try:
        return scan(args.repo, args.staged)
    except ScanError as error:
        finding(error.path, 0, error.category)
        return 2


if __name__ == "__main__":
    sys.exit(main())
