"""Opt-in Windows native commit attribution; never use its run for FPS acceptance.

Requires Frida in the selected Python environment. Hooks every virtual allocation
size, release, and section mapping, including Ex variants. This is page backing,
not individual process-heap object accounting. Records failures and releases;
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


def source_for(directory, heaps=False):
    source = SOURCE.replace("__TRACE_FILE__", json.dumps(str(directory / "native-commit.bin")))
    if heaps:
        source += pathlib.Path(__file__).with_name("Trace-NativeHeap.js").read_text(encoding="utf-8").replace(
            "__HEAP_FILE__", json.dumps(str(directory / "native-heap.bin"))).replace(
            "__HEAP_STACK_FILE__", json.dumps(str(directory / "native-heap-stacks.bin")))
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--frida-directory", type=pathlib.Path)
    parser.add_argument("--detach-marker", default="GalleryPerf warmupFrames=")
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
                script = session.create_script(SOURCE.replace("__TRACE_FILE__", json.dumps(str(out / "native-commit.bin"))))
                script.on("message", receive)
                script.load()
                print("Observing native commit/release, PID", target, flush=True)
                next_dump = time.monotonic() + 5
                finish_deadline = time.monotonic() + 120
                while process.poll() is None:
                    if time.monotonic() > finish_deadline:
                        raise TimeoutError("Native attribution exceeded its 120 second bound")
                    if args.detach_marker in (out / "run.log").read_text(encoding="utf-8", errors="replace"):
                        script.unload()
                        session.detach()
                        session = None
                        process.wait(timeout=30)
                        break
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
const nt=Process.getModuleByName('ntdll.dll'), k=Process.getModuleByName('kernel32.dll');
const open=new NativeFunction(k.getExportByName('CreateFileW'),'pointer',['pointer','uint32','uint32','pointer','uint32','uint32','pointer']);
const handle=open(Memory.allocUtf16String(__TRACE_FILE__),0x40000000,3,NULL,2,0,NULL);
if(handle.equals(ptr(-1)))throw new Error('Cannot open native allocation trace');
const cell=Memory.alloc(Process.pointerSize);cell.writePointer(handle);
// Allocation callbacks execute entirely in C. JS and JSON allocation inside a
// native allocator callback can perturb that allocator, especially at shutdown.
const native=new CModule(`
#include <gum/guminterceptor.h>
#include <stdint.h>
typedef struct {
 uint32_t kind,tid; int32_t status; uint32_t flags;
 uint64_t base,size,allocation_base; uint32_t frame_count,reserved; void* frames[96];
} Record;
typedef struct {void* base;void* allocation_base;uint32_t allocation_protect;uint32_t partition;
 uint64_t region_size;uint32_t state,protect,type,padding;} MemoryInfo;
typedef struct {void**base;uint64_t*size;uint64_t input_size,address,allocation;
 uint32_t flags,keep,count,kind;void*frames[96];} Call;
extern void* trace_file;
extern uint32_t write_file(void*,const void*,uint32_t,uint32_t*,void*);
extern void* lookup_function(uint64_t,uint64_t*,void*);
extern void* virtual_unwind(uint32_t,uint64_t,uint64_t,void*,void*,void**,uint64_t*,void*);
extern void stack_limits(uint64_t*,uint64_t*);
extern uint64_t virtual_query(void*,MemoryInfo*,uint64_t);
uint32_t capture_context(GumCpuContext*cpu,void**frames){
 uint64_t c[154]={0},image=0,establisher=0,low=0,high=0;void*handler=0;
 ((uint32_t*)c)[12]=0x10000b;
 c[15]=cpu->rax;c[16]=cpu->rcx;c[17]=cpu->rdx;c[18]=cpu->rbx;
 c[19]=cpu->rsp;c[20]=cpu->rbp;c[21]=cpu->rsi;c[22]=cpu->rdi;
 c[23]=cpu->r8;c[24]=cpu->r9;c[25]=cpu->r10;c[26]=cpu->r11;
 c[27]=cpu->r12;c[28]=cpu->r13;c[29]=cpu->r14;c[30]=cpu->r15;c[31]=cpu->rip;
 stack_limits(&low,&high);uint32_t n=0;
 while(n<96&&c[31]&&c[19]>=low&&c[19]+8<=high){
  uint64_t pc=c[31],sp=c[19];frames[n++]=(void*)(uintptr_t)pc;
  void*entry=lookup_function(pc,&image,0);
  if(!entry){
   // Windows unwind tables do not describe JVM JIT frames. Do not interpret
   // arbitrary Java frame data as native return PCs and invent deeper frames.
   MemoryInfo m={0};virtual_query((void*)(uintptr_t)pc,&m,sizeof(m));
   if(m.type!=0x1000000)break;
   c[31]=*((uint64_t*)(uintptr_t)sp);c[19]+=8;
  }
  else virtual_unwind(0,image,pc,entry,c,&handler,&establisher,0);
  if(c[31]==pc&&c[19]==sp)break;
 }
 return n;
}
extern uint64_t virtual_query(void*,MemoryInfo*,uint64_t);
extern uint32_t thread_id(void);
extern uint32_t tick_count(void);
extern uint32_t current_process_id(void);
extern uint32_t process_id(void*);
int current(void*p){return p==(void*)(intptr_t)-1||process_id(p)==current_process_id();}
void allocation_common(GumInvocationContext*ic,int extended){
 Call*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(Call));
 c->keep=current(gum_invocation_context_get_nth_argument(ic,0));if(!c->keep)return;
 c->base=gum_invocation_context_get_nth_argument(ic,1);
 c->size=gum_invocation_context_get_nth_argument(ic,extended?2:3);
 c->flags=(uint32_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,extended?3:4);
 c->kind=(c->flags&0x1000)?1:5;c->input_size=*c->size;
 c->count=capture_context(ic->cpu_context,c->frames);
}
void allocation_enter(GumInvocationContext*ic){allocation_common(ic,0);}
void allocation_ex_enter(GumInvocationContext*ic){allocation_common(ic,1);}
void allocation_leave(GumInvocationContext*ic){
 Call*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(Call));
 int32_t status=(int32_t)(uintptr_t)gum_invocation_context_get_return_value(ic);
 if(!c->keep)return;
 Record r={0}; MemoryInfo m={0};uint32_t written=0;
 r.kind=c->kind;r.status=status;r.tid=thread_id();r.reserved=tick_count();r.flags=c->flags;r.base=(uint64_t)(uintptr_t)*c->base;
 r.size=status<0?c->input_size:*c->size;
 if(status>=0)virtual_query(*c->base,&m,sizeof(m));
 r.allocation_base=(uint64_t)(uintptr_t)m.allocation_base;r.frame_count=c->count;
 for(uint32_t i=0;i<c->count;i++)r.frames[i]=c->frames[i];
 write_file(trace_file,&r,sizeof(r),&written,0);
}
void free_enter(GumInvocationContext*ic){
 Call*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(Call));
 c->keep=current(gum_invocation_context_get_nth_argument(ic,0));if(!c->keep)return;
 c->address=(uint64_t)(uintptr_t)*((void**)gum_invocation_context_get_nth_argument(ic,1));
 c->input_size=*((uint64_t*)gum_invocation_context_get_nth_argument(ic,2));
 c->flags=(uint32_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,3);
 c->kind=2;c->count=capture_context(ic->cpu_context,c->frames);
}
void free_leave(GumInvocationContext*ic){
 int32_t status=(int32_t)(uintptr_t)gum_invocation_context_get_return_value(ic);
 Call*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(Call));
 if(!c->keep)return;
 Record r={0};uint32_t written=0;r.kind=c->kind;r.status=status;r.tid=thread_id();r.reserved=tick_count();r.flags=c->flags;
 r.base=c->address;r.size=c->input_size;r.allocation_base=c->allocation;r.frame_count=c->count;
 for(uint32_t i=0;i<c->count;i++)r.frames[i]=c->frames[i];
 write_file(trace_file,&r,sizeof(r),&written,0);
}
void map_common(GumInvocationContext*ic,int extended){
 Call*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(Call));
 c->keep=current(gum_invocation_context_get_nth_argument(ic,1));if(!c->keep)return;
 c->base=gum_invocation_context_get_nth_argument(ic,2);
 c->size=gum_invocation_context_get_nth_argument(ic,extended?4:6);
 c->input_size=*c->size;c->flags=(uint32_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,extended?5:8);
 c->kind=3;c->count=capture_context(ic->cpu_context,c->frames);
}
void map_enter(GumInvocationContext*ic){map_common(ic,0);}
void map_ex_enter(GumInvocationContext*ic){map_common(ic,1);}
void unmap_enter(GumInvocationContext*ic){
 Call*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(Call));
 c->keep=current(gum_invocation_context_get_nth_argument(ic,0));if(!c->keep)return;
 c->address=(uint64_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,1);
 MemoryInfo m={0};virtual_query((void*)(uintptr_t)c->address,&m,sizeof(m));
 c->allocation=(uint64_t)(uintptr_t)m.allocation_base;c->input_size=0;c->flags=0;
 c->kind=4;c->count=capture_context(ic->cpu_context,c->frames);
}
`,{trace_file:cell,write_file:k.getExportByName('WriteFile'),lookup_function:nt.getExportByName('RtlLookupFunctionEntry'),virtual_unwind:nt.getExportByName('RtlVirtualUnwind'),stack_limits:k.getExportByName('GetCurrentThreadStackLimits'),
 virtual_query:k.getExportByName('VirtualQuery'),thread_id:k.getExportByName('GetCurrentThreadId'),tick_count:k.getExportByName('GetTickCount'),
 current_process_id:k.getExportByName('GetCurrentProcessId'),process_id:k.getExportByName('GetProcessId')});
const hooks=[];
for(const [name,enter,leave] of [
 ['NtAllocateVirtualMemory','allocation_enter','allocation_leave'],
 ['NtAllocateVirtualMemoryEx','allocation_ex_enter','allocation_leave'],
 ['NtFreeVirtualMemory','free_enter','free_leave'],
 ['NtMapViewOfSection','map_enter','allocation_leave'],
 ['NtMapViewOfSectionEx','map_ex_enter','allocation_leave'],
 ['NtUnmapViewOfSection','unmap_enter','free_leave'],
 ['NtUnmapViewOfSectionEx','unmap_enter','free_leave']]){
 const address=nt.findExportByName(name);
 if(address){Interceptor.attach(address,{onEnter:native[enter],onLeave:native[leave]});hooks.push(name);}
}
function modules(){send({kind:'modules',modules:Process.enumerateModules().map(m=>({name:m.name,base:m.base.toString(),size:m.size}))});}
const tick=new NativeFunction(k.getExportByName('GetTickCount'),'uint32',[]);
modules();setInterval(modules,1000);send({kind:'ready',recordBytes:816,tick:tick(),epochMillis:Date.now(),hooks,minimumCommitBytes:0});
'''

if __name__ == "__main__":
    raise SystemExit(main())
