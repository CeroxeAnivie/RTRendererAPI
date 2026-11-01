"""Bounded runtime memory census, optional native stacks, and pre-OOM JFR/NMT.

Runtime arguments remain data. Native hooks are explicitly opt-in; none of this
tool's diagnostic work is part of a production or FPS acceptance run.
"""
import argparse
import concurrent.futures
import csv
import ctypes
import gzip
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import time

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def focus_owned_viewport(pid):
    """Explicit acceptance option: activate only this child process's visible window."""
    user = ctypes.WinDLL("user32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    callback_type = ctypes.WINFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_ssize_t)
    user.EnumWindows.argtypes = [callback_type, ctypes.c_ssize_t]
    user.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
    user.GetForegroundWindow.restype = ctypes.c_void_p
    for name in ("IsWindowVisible", "IsIconic", "SetForegroundWindow", "BringWindowToTop"):
        getattr(user, name).argtypes = [ctypes.c_void_p]
    user.ShowWindowAsync.argtypes = [ctypes.c_void_p, ctypes.c_int]
    windows = []

    @callback_type
    def visit(window, _):
        owner = ctypes.c_ulong()
        user.GetWindowThreadProcessId(window, ctypes.byref(owner))
        if owner.value == pid and user.IsWindowVisible(window):
            windows.append(window)
        return 1

    user.EnumWindows(visit, 0)
    if not windows:
        return False
    window = windows[0]
    if user.IsIconic(window):
        user.ShowWindowAsync(window, 9)
    foreground = user.GetForegroundWindow()
    current = kernel.GetCurrentThreadId()
    threads = {user.GetWindowThreadProcessId(w, None) for w in (window, foreground) if w}
    attached = []
    # Ensure this observer owns an input queue before the brief focus handoff.
    message = ctypes.create_string_buffer(64)
    user.PeekMessageW(message, None, 0, 0, 0)
    try:
        for thread in threads:
            if thread != current and user.AttachThreadInput(current, thread, True):
                attached.append(thread)
        user.BringWindowToTop(window)
        user.SetForegroundWindow(window)
        return user.GetForegroundWindow() == window
    finally:
        for thread in reversed(attached):
            user.AttachThreadInput(current, thread, False)


class Counters(ctypes.Structure):
    _fields_ = [("cb", ctypes.c_ulong), ("pageFaults", ctypes.c_ulong)] + [
        (name, ctypes.c_size_t) for name in (
            "peakWorkingSet", "workingSet", "quotaPeakPaged", "quotaPaged",
            "quotaPeakNonPaged", "quotaNonPaged", "pagefile", "peakPagefile", "private")]


class Region(ctypes.Structure):
    _fields_ = [("base", ctypes.c_void_p), ("allocation", ctypes.c_void_p),
                ("allocationProtect", ctypes.c_uint32), ("partition", ctypes.c_uint16),
                ("size", ctypes.c_size_t), ("state", ctypes.c_uint32),
                ("protect", ctypes.c_uint32), ("type", ctypes.c_uint32)]


class GpuProcessMemory:
    """Per-process Windows GPU commit, not unique physical VRAM residency."""
    class Value(ctypes.Structure):
        _fields_ = [("status", ctypes.c_ulong), ("value", ctypes.c_double)]

    def __init__(self):
        class Item(ctypes.Structure):
            _fields_ = [("name", ctypes.c_wchar_p), ("value", GpuProcessMemory.Value)]
        self.item_type = Item
        self.api = ctypes.WinDLL("pdh")
        self.query = ctypes.c_void_p()
        self.api.PdhOpenQueryW.argtypes = [ctypes.c_wchar_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)]
        self.api.PdhAddEnglishCounterW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)]
        self.api.PdhCollectQueryData.argtypes = [ctypes.c_void_p]
        self.api.PdhGetFormattedCounterArrayW.argtypes = [ctypes.c_void_p, ctypes.c_ulong,
            ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong), ctypes.c_void_p]
        self.api.PdhCloseQuery.argtypes = [ctypes.c_void_p]
        if self.api.PdhOpenQueryW(None, 0, ctypes.byref(self.query)):
            raise OSError("Cannot open GPU memory counters")
        self.counters = []
        try:
            for name in ("Dedicated Usage", "Shared Usage"):
                counter = ctypes.c_void_p()
                status = self.api.PdhAddEnglishCounterW(self.query, "\\GPU Process Memory(*)\\" + name,
                                                       0, ctypes.byref(counter))
                if status:
                    raise OSError("GPU memory counter unavailable: " + name)
                self.counters.append(counter)
        except BaseException:
            self.close()
            raise

    def sample(self, pid):
        if self.api.PdhCollectQueryData(self.query):
            return [None, None]
        result = []
        for counter in self.counters:
            size, count = ctypes.c_ulong(), ctypes.c_ulong()
            status = self.api.PdhGetFormattedCounterArrayW(counter, 0x200, ctypes.byref(size), ctypes.byref(count), None)
            if (status & 0xffffffff) != 0x800007d2:  # PDH_MORE_DATA
                result.append(None)
                continue
            buffer = ctypes.create_string_buffer(size.value)
            status = self.api.PdhGetFormattedCounterArrayW(counter, 0x200, ctypes.byref(size), ctypes.byref(count), buffer)
            if status:
                result.append(None)
                continue
            items = ctypes.cast(buffer, ctypes.POINTER(self.item_type))
            values = [items[i].value.value for i in range(count.value)
                      if items[i].name.startswith(f"pid_{pid}_") and items[i].value.status in (0, 1)]
            result.append(int(sum(values)) if values else None)
        return result

    def close(self):
        if self.query:
            self.api.PdhCloseQuery(self.query)
            self.query = None


def census(kernel, psapi, handle, names):
    started = time.time()
    address = 0
    regions = []
    groups = {}
    totals = {"private": 0, "mapped": 0, "image": 0}
    error = 0
    while True:
        value = Region()
        if not kernel.VirtualQueryEx(handle, ctypes.c_void_p(address), ctypes.byref(value), ctypes.sizeof(value)):
            error = ctypes.get_last_error()
            if not address:
                raise ctypes.WinError(error)
            break
        base, allocation, size = value.base or 0, value.allocation or 0, value.size
        if value.state == 0x1000:  # Every committed virtual region, including guard/no-access pages.
            kind = {0x20000: "private", 0x40000: "mapped", 0x1000000: "image"}.get(value.type, "unknown")
            totals[kind] = totals.get(kind, 0) + size
            regions.append([base, allocation, size, value.type, value.protect])
            key = (allocation, value.type)
            groups[key] = groups.get(key, 0) + size
            if value.type in (0x40000, 0x1000000) and key not in names:
                text = ctypes.create_unicode_buffer(32768)
                count = psapi.GetMappedFileNameW(handle, ctypes.c_void_p(base), text, len(text))
                names[key] = text.value if count else ""
        next_address = base + size
        if next_address <= address:
            break
        address = next_address
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    if not psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
        raise ctypes.WinError(ctypes.get_last_error())
    return {"epochSeconds": time.time(), "scanStartedEpochSeconds": started,
            "scanDurationSeconds": time.time() - started, "terminalQueryError": error,
            "processPrivateCommit": counters.private,
            "virtualCommitByType": totals,
            # Keep the signed difference visible. Kernel/driver charges and image
            # COW are not equivalent to MEM_PRIVATE virtual regions; do not label
            # this residual as a leak or silently force it to zero.
            "processCommitMinusPrivateVirtual": counters.private - totals["private"],
            "allocations": [{"base": base, "type": kind, "bytes": size,
                             "file": names.get((base, kind), "")}
                            for (base, kind), size in sorted(groups.items(), key=lambda item: item[1], reverse=True)],
            "regions": regions}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--native-stacks", action="store_true")
    parser.add_argument("--native-heaps", action="store_true", help="Trace all Rtl heap object lifetimes and full allocation stacks")
    parser.add_argument("--census-interval", type=float, default=1.0)
    parser.add_argument("--detach-marker", default="GalleryPerf warmupFrames=")
    parser.add_argument("--frida-directory", type=pathlib.Path)
    parser.add_argument("--lightweight", action="store_true", help="Only process/GPU counters; no census, hooks or jcmd dumps")
    parser.add_argument("--memory-limit-gb", type=float, default=10)
    parser.add_argument("--foreground", action="store_true", help="Activate this run's visible viewport once for explicit FG acceptance")
    args = parser.parse_args()
    if args.native_heaps and not args.native_stacks:
        parser.error("--native-heaps requires --native-stacks")
    if args.census_interval < 0.1:
        parser.error("--census-interval must be at least 0.1 seconds")
    if args.lightweight and args.native_stacks:
        parser.error("--lightweight cannot enable native hooks")
    if args.memory_limit_gb < 1:
        parser.error("--memory-limit-gb must be at least 1")
    root = pathlib.Path(__file__).resolve().parent.parent
    directory = args.directory.resolve()
    # The manifest must identify the frozen artifact actually used by this run.
    manifest = json.loads((directory / "runtime.json").read_text(encoding="utf-8-sig"))
    if "arguments" not in manifest:
        raise ValueError("runtime.json requires arguments")
    (directory / "probe-ready").unlink(missing_ok=True)
    (directory / "run.ps1").write_text(
        "$ErrorActionPreference='Stop'\n"
        "$meta=Get-Content (Join-Path $PSScriptRoot 'runtime.json') -Raw|ConvertFrom-Json\n"
        "$java=if($meta.executable){$meta.executable}else{'java'}\n"
        f"& tools/Invoke-BoundedBuild.ps1 -FilePath $java -ArgumentList $meta.arguments -MemoryLimitGB {args.memory_limit_gb}\n",
        encoding="utf-8")
    with (directory / "run.ps1").open("a", encoding="utf-8") as launcher:
        launcher.write("exit $LASTEXITCODE\n")
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(Counters), ctypes.c_ulong]
    psapi.GetMappedFileNameW.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_ulong]
    kernel.VirtualQueryEx.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(Region), ctypes.c_size_t]
    kernel.VirtualQueryEx.restype = ctypes.c_size_t
    handle = None
    session = script = records = None
    diagnostics = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    dump = None
    names = {}
    gpu = None
    try:
        gpu = GpuProcessMemory()
    except OSError as error:
        print("GPU process counters unavailable:", error, flush=True)
    with (directory / "run.log").open("w", encoding="utf-8") as log, \
            (directory / "process-memory.csv").open("w", encoding="utf-8", newline="") as memory, \
            gzip.open(directory / "virtual-memory.jsonl.gz", "wt", encoding="utf-8") as virtual:
        writer = csv.writer(memory)
        writer.writerow(["epochSeconds", "elapsedSeconds", "privateCommitBytes", "workingSetBytes",
                         "gpuDedicatedCommitBytes", "gpuSharedCommitBytes"])
        process = subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(directory / "run.ps1")],
                                   cwd=root, stdout=log, stderr=subprocess.STDOUT)
        start = time.monotonic()
        next_dump = start + 5
        next_census = start
        dump_number = 0
        target = None
        focused = False
        try:
            while process.poll() is None:
                now = time.monotonic()
                if now - start > args.timeout:
                    raise TimeoutError("Memory observation run exceeded its time bound")
                if target is None:
                    match = re.search(r"(?:Use jcmd |Bounded build root PID=)(\d+)", (directory / "run.log").read_text(encoding="utf-8", errors="replace"))
                    if match:
                        target = int(match.group(1))
                        # VirtualQueryEx/GetMappedFileName require QUERY_INFORMATION,
                        # not just QUERY_LIMITED_INFORMATION.
                        handle = kernel.OpenProcess(0x400 | 0x10, False, target)
                        if not handle:
                            raise ctypes.WinError(ctypes.get_last_error())
                        print("Watching native commit, PID", target, flush=True)
                        if args.native_stacks:
                            if args.frida_directory:
                                sys.path.insert(0, str(args.frida_directory.resolve()))
                            import frida
                            specification = importlib.util.spec_from_file_location("native_commit", root / "tools/Trace-NativeCommit.py")
                            module = importlib.util.module_from_spec(specification)
                            specification.loader.exec_module(module)
                            records = (directory / "allocations.jsonl").open("w", encoding="utf-8")
                            def receive(message, data):
                                records.write(json.dumps({"time": time.time(), "message": message}) + "\n")
                                records.flush()
                            session = frida.attach(target)
                            print("Native observer attached", flush=True)
                            script = session.create_script(module.source_for(directory, args.native_heaps))
                            print("Native observer script compiled", flush=True)
                            script.on("message", receive)
                            script.load()
                            print("Native observer hooks installed", flush=True)
                        if not args.lightweight:
                            virtual.write(json.dumps(census(kernel, psapi, handle, names)) + "\n")
                            virtual.flush()
                        (directory / "probe-ready").touch()
                if handle:
                    if args.foreground and not focused:
                        focused = focus_owned_viewport(target)
                        if focused:
                            print("Acceptance viewport owns the foreground, PID", target, flush=True)
                    counters = Counters()
                    counters.cb = ctypes.sizeof(counters)
                    if psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                        gpu_values = gpu.sample(target) if gpu else [None, None]
                        writer.writerow([time.time(), now - start, counters.private, counters.workingSet, *gpu_values])
                        memory.flush()
                    if not args.lightweight and now >= next_census:
                        try:
                            snapshot = census(kernel, psapi, handle, names)
                        except OSError:
                            # The OS denies queries while a terminated target is being
                            # reaped; preserve the real process exit and earlier evidence.
                            exit_code = ctypes.c_ulong()
                            kernel.GetExitCodeProcess.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
                            if kernel.GetExitCodeProcess(handle, ctypes.byref(exit_code)) and exit_code.value != 259:
                                break
                            raise
                        virtual.write(json.dumps(snapshot) + "\n")
                        virtual.flush()
                        next_census = time.monotonic() + args.census_interval
                    if script and args.detach_marker in (directory / "run.log").read_text(encoding="utf-8", errors="replace"):
                        if args.native_heaps:
                            script.exports_sync.flush()
                        script.unload()
                        session.detach()
                        session = script = None
                    if not args.lightweight and now >= next_dump and (dump is None or dump.done()):
                        if dump is not None:
                            dump.result()
                        checkpoint = directory / f"checkpoint-{dump_number % 2}.jfr"
                        command_file = directory / f"checkpoint-{dump_number % 2}.commands"
                        command_file.write_text("JFR.dump name=1 filename=" + str(checkpoint)
                                                + "\nVM.native_memory summary scale=KB\n", encoding="utf-8")
                        def checkpoint_dump(command_file=command_file, number=dump_number):
                            with (directory / f"nmt-{number:04d}.txt").open("w", encoding="utf-8") as output:
                                subprocess.run(["jcmd", str(target), "-f", str(command_file)],
                                               stdout=output, stderr=subprocess.STDOUT, timeout=15)
                        dump = diagnostics.submit(checkpoint_dump)
                        dump_number += 1
                        next_dump = time.monotonic() + 5
                time.sleep(1 if args.lightweight else 0.2)
        finally:
            if session:
                try:
                    if script and args.native_heaps:
                        script.exports_sync.flush()
                    session.detach()
                except Exception:
                    pass
            if records:
                records.close()
            if handle:
                kernel.CloseHandle(handle)
            if process.poll() is None:
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=10)
            diagnostics.shutdown(wait=True)
            if gpu:
                gpu.close()
    print((directory / "run.log").read_text(encoding="utf-8", errors="replace")[-2400:])
    return process.returncode


if __name__ == "__main__":
    raise SystemExit(main())
