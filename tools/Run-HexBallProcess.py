"""Use file handles so NGX updater children cannot hold PowerShell pipes open."""
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

prefix = Path(sys.argv[1])
settings = json.loads(Path(str(prefix)+"-settings.json").read_text(encoding="utf-8-sig"))
command = [settings["Java"], *settings["Jvm"], settings["Entry"], *settings["Arguments"]]
with Path(str(prefix)+".log").open("wb") as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, close_fds=True,
                               creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
    presentmon = None
    pm_log = None
    try:
        if settings.get("PresentMon"):
            pm_log = Path(str(prefix)+"-presentmon.log").open("wb")
            pm_command = [str(Path("build/downloads/PresentMon-2.6.0-x64.exe").resolve()),
                          "--process_id",str(process.pid),"--session_name",f"RTR-{process.pid}",
                          "--output_file",str(prefix)+"-presentmon.csv","--no_console_stats",
                          "--terminate_on_proc_exit","--track_frame_type","--track_hybrid_present"]
            presentmon = subprocess.Popen(pm_command, stdout=pm_log, stderr=subprocess.STDOUT,
                                         creationflags=subprocess.CREATE_NO_WINDOW)
        code = process.wait()
    finally:
        if process.poll() is None: process.terminate(); process.wait()
        if presentmon is not None:
            try: presentmon.wait(timeout=5)
            except subprocess.TimeoutExpired: presentmon.terminate(); presentmon.wait()
        if pm_log is not None: pm_log.close()
Path(str(prefix)+"-exit.json").write_text(json.dumps({"ExitCode": code,
    "EndedAt": datetime.now().astimezone().isoformat()}, indent=2)+"\n", encoding="utf-8")
sys.exit(code)
