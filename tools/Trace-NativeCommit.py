"""Opt-in Windows native commit attribution; never use its run for FPS acceptance.

Requires Frida in the selected Python environment. Hooks virtual commit/release,
not Vulkan entry points or the process heap. Records releases as well as commits;
allocation traffic must not be reported as retained memory. Full x64 unwinding
uses the OS unwind tables, beyond Frida's short default backtrace.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--frida-directory", type=pathlib.Path)
    args = parser.parse_args()
    if args.frida_directory:
        sys.path.insert(0, str(args.frida_directory.resolve()))
    import frida

    root = pathlib.Path(__file__).resolve().parent.parent
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    meta = json.loads(args.runtime.read_text(encoding="utf-8-sig"))
    # Paths and arguments stay data: do not interpolate them into shell code.
    (out / "runtime.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    (out / "run.ps1").write_text(
        "$ErrorActionPreference='Stop'\n"
        "$meta=Get-Content (Join-Path $PSScriptRoot 'runtime.json') -Raw|ConvertFrom-Json\n"
        "& tools/Invoke-BoundedBuild.ps1 -FilePath java -ArgumentList $meta.arguments -MemoryLimitGB 3\n",
        encoding="utf-8",
    )
    with (out / "run.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(out / "run.ps1")],
            cwd=root, stdout=log, stderr=subprocess.STDOUT,
        )
        session = None
        try:
            deadline = time.monotonic() + 30
            target = None
            while process.poll() is None and time.monotonic() < deadline:
                match = re.search(r"Use jcmd (\d+) JFR", (out / "run.log").read_text(encoding="utf-8", errors="replace"))
                if match:
                    target = int(match.group(1))
                    break
                time.sleep(0.03)
            if target is None:
                raise RuntimeError("Target must start a JFR recording so its exact PID is known")
            session = frida.attach(target)
            with (out / "allocations.jsonl").open("w", encoding="utf-8") as records:
                def receive(message, data):
                    records.write(json.dumps({"time": time.time(), "message": message}) + "\n")
                    records.flush()
                script = session.create_script(SOURCE)
                script.on("message", receive)
                script.load()
                print("Observing native commit/release, PID", target, flush=True)
                next_dump = time.monotonic() + 5
                while process.poll() is None:
                    if time.monotonic() >= next_dump:
                        # Save before the hard memory limit can prevent the emergency dump.
                        subprocess.run(["jcmd", str(target), "JFR.dump", "name=1", "filename=" + str(out / "checkpoint.jfr")],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                        next_dump = time.monotonic() + 5
                    time.sleep(0.1)
        finally:
            if session is not None:
                try:
                    session.detach()
                except frida.InvalidOperationError:
                    pass
            # Closing this job host also closes its KILL_ON_JOB_CLOSE job.
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
    print((out / "run.log").read_text(encoding="utf-8", errors="replace")[-2400:])
    return process.returncode


SOURCE = r'''
const nt = Process.getModuleByName('ntdll.dll');
const lookup = new NativeFunction(nt.getExportByName('RtlLookupFunctionEntry'), 'pointer', ['uint64','pointer','pointer']);
const unwind = new NativeFunction(nt.getExportByName('RtlVirtualUnwind'), 'pointer',
  ['uint32','uint64','uint64','pointer','pointer','pointer','pointer','pointer']);
const query = new NativeFunction(Process.getModuleByName('kernel32.dll').getExportByName('VirtualQuery'),
  'uint64', ['pointer','pointer','uint64']);
const registers = {rax:120,rcx:128,rdx:136,rbx:144,rsp:152,rbp:160,rsi:168,rdi:176,
  r8:184,r9:192,r10:200,r11:208,r12:216,r13:224,r14:232,r15:240,rip:248};
function stack(context) {
  const c=Memory.alloc(1232), base=Memory.alloc(8), data=Memory.alloc(8), frame=Memory.alloc(8);
  c.add(48).writeU32(0x10000b);
  for(const [name,offset] of Object.entries(registers))c.add(offset).writePointer(context[name]);
  const result=[];
  for(let i=0;i<96;i++) {
    const pc=c.add(248).readPointer(), sp=c.add(152).readPointer();
    if(pc.isNull()||sp.isNull())break;
    const m=Process.findModuleByAddress(pc);
    result.push(m?m.name+'+'+pc.sub(m.base).toString():pc.toString());
    try {
      const entry=lookup(uint64(pc.toString()),base,NULL);
      if(entry.isNull()){c.add(248).writePointer(sp.readPointer());c.add(152).writePointer(sp.add(8));}
      else unwind(0,base.readU64(),uint64(pc.toString()),entry,c,data,frame,NULL);
      if(c.add(248).readPointer().equals(pc)&&c.add(152).readPointer().equals(sp))break;
    } catch(e){result.push('unwind-stop:'+e);break;}
  }
  return result;
}
Interceptor.attach(nt.getExportByName('NtAllocateVirtualMemory'), {
  onEnter(a){this.base=a[1];this.size=a[3];this.flags=a[4].toUInt32();
    if((this.flags&0x1000)&&Number(this.size.readU64())>=65536)this.stack=stack(this.context);},
  onLeave(r){if(this.stack&&r.toInt32()===0){const b=this.base.readPointer(),info=Memory.alloc(48);
    query(b,info,48);send({kind:'commit',tid:Process.getCurrentThreadId(),base:b.toString(),
      allocationBase:info.add(8).readPointer().toString(),size:Number(this.size.readU64()),flags:this.flags,stack:this.stack});}}
});
Interceptor.attach(nt.getExportByName('NtFreeVirtualMemory'), {
  onEnter(a){this.base=a[1].readPointer().toString();this.size=Number(a[2].readU64());this.flags=a[3].toUInt32();},
  onLeave(r){if(r.toInt32()===0)send({kind:'free',tid:Process.getCurrentThreadId(),base:this.base,size:this.size,flags:this.flags});}
});
send({kind:'ready',modules:Process.enumerateModules().map(m=>({name:m.name,base:m.base.toString(),size:m.size}))});
'''

if __name__ == "__main__":
    raise SystemExit(main())
