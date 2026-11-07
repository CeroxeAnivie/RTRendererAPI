"""Export immutable upstream Git objects without copying a patched work tree."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile


PIN = "303b408ab81790a5051abd7573830b7a4112eb8e"


def git(repository: Path, *arguments: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(repository), *arguments])


def export_repository(repository: Path, commit: str, destination: Path,
                      logical: str, repositories: list[dict]) -> None:
    git(repository, "cat-file", "-e", commit + "^{commit}")
    destination.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(
        ["git", "-C", str(repository), "archive", "--format=tar", commit],
        stdout=subprocess.PIPE,
    )
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for member in archive:
                target = destination / member.name
                if target.exists() or target.is_symlink():
                    if member.isdir() and target.is_dir():
                        continue
                    if member.issym() and target.is_symlink() and str(target.readlink()) == member.linkname:
                        continue
                    if member.isfile() and target.is_file() and not target.is_symlink():
                        incoming = archive.extractfile(member)
                        with target.open("rb") as existing:
                            if incoming is not None and hashlib.file_digest(incoming, "sha256").digest() == hashlib.file_digest(existing, "sha256").digest():
                                continue
                    raise RuntimeError(f"Existing source differs from official Git objects: {target}")
                archive.extract(member, destination, filter="data")
        # tarfile stops at the logical end marker; Git may still be writing tar
        # padding. Drain the pipe before closing it, otherwise Git sees SIGPIPE.
        if process.stdout is not None:
            process.stdout.read()
    finally:
        if process.stdout is not None:
            process.stdout.close()
    if process.wait() != 0:
        raise RuntimeError(f"Could not export official Git objects: {logical}")
    repositories.append({"path": logical, "commit": commit})
    entries = git(repository, "ls-tree", "-rz", commit).split(b"\0")
    for entry in entries:
        if not entry:
            continue
        identity, raw_path = entry.split(b"\t", 1)
        mode, kind, object_id = identity.decode("ascii").split(" ")
        if mode != "160000" or kind != "commit":
            continue
        relative = raw_path.decode("utf-8")
        child_logical = relative if logical == "." else logical + "/" + relative
        if child_logical == "Assets":
            repositories.append({
                "path": child_logical,
                "commit": object_id,
                "exported": False,
                "reason": "Official release runtime supplies scene assets; not core source.",
            })
            continue
        export_repository(repository / relative, object_id,
                          destination / relative, child_logical, repositories)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--resume", action="store_true", help="Verify and retain matching partial-export files.")
    arguments = parser.parse_args()
    repository = arguments.repository.resolve()
    output = arguments.output.resolve()
    manifest_path = arguments.manifest.resolve()
    if output.exists() and any(output.iterdir()) and not arguments.resume:
        raise RuntimeError(f"Refusing to overwrite an existing source export: {output}")
    if manifest_path.exists():
        raise RuntimeError(f"Refusing to overwrite an existing identity manifest: {manifest_path}")
    repositories: list[dict] = []
    export_repository(repository, PIN, output, ".", repositories)
    files = []
    total = 0
    for path in sorted(output.rglob("*")):
        if not path.is_file():
            continue
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        size = path.stat().st_size
        files.append({"path": path.relative_to(output).as_posix(), "bytes": size, "sha256": digest})
        total += size
    manifest = {
        "format": "rtr-upstream-git-export-v1",
        "release": "v.1.9.0",
        "commit": PIN,
        "sourceRoot": str(output),
        "origin": "https://github.com/NVIDIA-RTX/RTXPT.git",
        "repositories": repositories,
        "files": files,
        "sourceBytes": total,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"source": str(output), "manifest": str(manifest_path),
                      "repositories": len(repositories), "files": len(files), "bytes": total}, ensure_ascii=False))


if __name__ == "__main__":
    main()
