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
    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, close_fds=True,
                            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
Path(str(prefix)+"-exit.json").write_text(json.dumps({"ExitCode": result.returncode,
    "EndedAt": datetime.now().astimezone().isoformat()}, indent=2)+"\n", encoding="utf-8")
sys.exit(result.returncode)
