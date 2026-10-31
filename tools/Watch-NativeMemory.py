"""Bounded Java run with external process memory sampling and pre-OOM JFR dumps.

No injected code, native hooks, or renderer synchronization. Runtime arguments
are read from a JSON manifest and never interpolated into a shell command.
"""
import argparse
import csv
import ctypes
import json
import pathlib
import re
import subprocess
import time


class Counters(ctypes.Structure):
    _fields_ = [("cb", ctypes.c_ulong), ("pageFaults", ctypes.c_ulong)] + [
        (name, ctypes.c_size_t) for name in (
            "peakWorkingSet", "workingSet", "quotaPeakPaged", "quotaPaged",
            "quotaPeakNonPaged", "quotaNonPaged", "pagefile", "peakPagefile", "private")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    directory = args.directory.resolve()
    # The manifest must identify the frozen artifact actually used by this run.
    manifest = json.loads((directory / "runtime.json").read_text(encoding="utf-8-sig"))
    if "arguments" not in manifest:
        raise ValueError("runtime.json requires arguments")
    (directory / "run.ps1").write_text(
        "$ErrorActionPreference='Stop'\n"
        "$meta=Get-Content (Join-Path $PSScriptRoot 'runtime.json') -Raw|ConvertFrom-Json\n"
        "& tools/Invoke-BoundedBuild.ps1 -FilePath java -ArgumentList $meta.arguments -MemoryLimitGB 3\n",
        encoding="utf-8")
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(Counters), ctypes.c_ulong]
    handle = None
    with (directory / "run.log").open("w", encoding="utf-8") as log, \
            (directory / "process-memory.csv").open("w", encoding="utf-8", newline="") as memory:
        writer = csv.writer(memory)
        writer.writerow(["epochSeconds", "elapsedSeconds", "privateCommitBytes", "workingSetBytes"])
        process = subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(directory / "run.ps1")],
                                   cwd=root, stdout=log, stderr=subprocess.STDOUT)
        start = time.monotonic()
        next_dump = start + 5
        dump_number = 0
        target = None
        try:
            while process.poll() is None:
                now = time.monotonic()
                if now - start > args.timeout:
                    raise TimeoutError("Memory observation run exceeded its time bound")
                if target is None:
                    match = re.search(r"Use jcmd (\d+) JFR", (directory / "run.log").read_text(encoding="utf-8", errors="replace"))
                    if match:
                        target = int(match.group(1))
                        handle = kernel.OpenProcess(0x1000 | 0x10, False, target)
                        if not handle:
                            raise ctypes.WinError(ctypes.get_last_error())
                        print("Watching native commit, PID", target, flush=True)
                if handle:
                    counters = Counters()
                    counters.cb = ctypes.sizeof(counters)
                    if psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                        writer.writerow([time.time(), now - start, counters.private, counters.workingSet])
                        memory.flush()
                    if now >= next_dump:
                        checkpoint = directory / f"checkpoint-{dump_number % 2}.jfr"
                        subprocess.run(["jcmd", str(target), "JFR.dump", "name=1", "filename=" + str(checkpoint)],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                        dump_number += 1
                        next_dump = time.monotonic() + 5
                time.sleep(0.2)
        finally:
            if handle:
                kernel.CloseHandle(handle)
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
    print((directory / "run.log").read_text(encoding="utf-8", errors="replace")[-2400:])
    return process.returncode


if __name__ == "__main__":
    raise SystemExit(main())
