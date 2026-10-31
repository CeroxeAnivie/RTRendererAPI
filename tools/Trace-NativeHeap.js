// Appended to Trace-NativeCommit.py's SOURCE only for explicit heap diagnostics.
// Full stacks are interned once; allocation/free records are 48 bytes each.
// The callbacks never enter JavaScript or allocate bookkeeping heap objects.
const heapFile = open(Memory.allocUtf16String(__HEAP_FILE__), 0x40000000, 3, NULL, 2, 0, NULL);
const stackFile = open(Memory.allocUtf16String(__HEAP_STACK_FILE__), 0x40000000, 3, NULL, 2, 0, NULL);
if (heapFile.equals(ptr(-1)) || stackFile.equals(ptr(-1))) throw new Error('Cannot open heap traces');
const heapHandles = Memory.alloc(24);
heapHandles.writePointer(heapFile);
heapHandles.add(8).writePointer(stackFile);
const allocTls = new NativeFunction(k.getExportByName('TlsAlloc'), 'uint32', []);
const heapTls = allocTls();
if (heapTls === 0xffffffff) throw new Error('Cannot allocate observer recursion guard');
heapHandles.add(16).writeU32(heapTls);
// CModule data pages are read-only. All mutable bookkeeping lives in explicit
// writable storage owned by this script, never in C static/BSS variables.
const heapState = Memory.alloc(80 + 65520 + 65536 * 24);
const heapNative = new CModule(`
#include <gum/guminterceptor.h>
#include <stdint.h>
typedef struct {uint32_t kind,tid,tick,stack;uint64_t heap,base,old_base,size;} HeapRecord;
typedef struct {uint32_t kind,tid;int32_t status;uint32_t flags;
 uint64_t base,size,allocation_base;uint32_t count,tick;void*frames[96];} StackRecord;
typedef struct {uint64_t a,b;uint32_t id,padding;} StackEntry;
typedef struct {uint64_t heap,old_base,size;uint32_t active,kind,stack;} HeapCall;
extern void* heap_file;extern void* stack_file;extern uint32_t tls_slot;
extern uint32_t write_file(void*,const void*,uint32_t,uint32_t*,void*);
extern uint32_t capture_context(GumCpuContext*,void**);
extern uint32_t thread_id(void);extern uint32_t tick_count(void);
extern void* tls_get(uint32_t);extern uint32_t tls_set(uint32_t,void*);
extern void acquire(void*);extern void release(void*);
extern void* lock;
extern StackEntry stacks[65536];
extern uint32_t next_stack,used;
extern uint8_t buffer[65520];
extern uint64_t counters[8];
void flush_locked(void){
 if(!used)return;uint32_t written=0;
 if(!write_file(heap_file,buffer,used,&written,0)||written!=used)counters[6]++;
 counters[5]+=written;used=0;
}
void flush_heap(void){acquire(&lock);flush_locked();release(&lock);}
uint32_t intern(GumCpuContext*cpu){
 void*frames[96];uint32_t count=capture_context(cpu,frames);
 uint64_t a=1469598103934665603ULL,b=1099511628211ULL;
 for(uint32_t i=0;i<count;i++){a=(a^(uint64_t)(uintptr_t)frames[i])*1099511628211ULL;
 b=(b+(uint64_t)(uintptr_t)frames[i])*1469598103934665603ULL;}
 a^=count;b^=count;uint32_t slot=(uint32_t)a&65535;
 acquire(&lock);
 for(uint32_t probe=0;probe<65536;probe++,slot=(slot+1)&65535){
  StackEntry*entry=&stacks[slot];
  if(entry->id&&entry->a==a&&entry->b==b){uint32_t id=entry->id;release(&lock);return id;}
  if(entry->id)continue;
  entry->id=++next_stack;entry->a=a;entry->b=b;
  StackRecord record={0};record.kind=10;record.base=entry->id;record.count=count;record.tick=tick_count();
  for(uint32_t i=0;i<count;i++)record.frames[i]=frames[i];
  uint32_t written=0;
  if(!write_file(stack_file,&record,sizeof(record),&written,0)||written!=sizeof(record))counters[6]++;
  counters[4]++;uint32_t id=entry->id;release(&lock);return id;
 }
 // No silent sampling when the intern table fills: emit another full stack.
 StackRecord record={0};record.kind=10;record.base=++next_stack;record.count=count;record.tick=tick_count();
 for(uint32_t i=0;i<count;i++)record.frames[i]=frames[i];
 uint32_t written=0;if(!write_file(stack_file,&record,sizeof(record),&written,0)||written!=sizeof(record))counters[6]++;
 counters[4]++;counters[7]++;uint32_t id=record.base;release(&lock);return id;
}
void heap_enter_common(GumInvocationContext*ic,uint32_t kind){
 HeapCall*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(HeapCall));c->active=0;
 if(tls_get(tls_slot))return;tls_set(tls_slot,(void*)1);c->active=1;c->kind=kind;
 c->heap=(uint64_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,0);
 c->old_base=kind==2||kind==3?(uint64_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,2):0;
 c->size=kind==1?(uint64_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,2):
         kind==3?(uint64_t)(uintptr_t)gum_invocation_context_get_nth_argument(ic,3):0;
 c->stack=intern(ic->cpu_context);
}
void heap_alloc_enter(GumInvocationContext*ic){heap_enter_common(ic,1);}
void heap_free_enter(GumInvocationContext*ic){heap_enter_common(ic,2);}
void heap_realloc_enter(GumInvocationContext*ic){heap_enter_common(ic,3);}
void heap_destroy_enter(GumInvocationContext*ic){heap_enter_common(ic,4);}
void heap_leave(GumInvocationContext*ic){
 HeapCall*c=gum_invocation_context_get_listener_invocation_data(ic,sizeof(HeapCall));if(!c->active)return;
 HeapRecord record={0};record.kind=c->kind;record.tid=thread_id();record.tick=tick_count();record.stack=c->stack;
 record.heap=c->heap;record.old_base=c->old_base;record.size=c->size;
 record.base=(uint64_t)(uintptr_t)gum_invocation_context_get_return_value(ic);
 acquire(&lock);if(used+sizeof(record)>sizeof(buffer))flush_locked();
 HeapRecord*output=(HeapRecord*)(buffer+used);*output=record;used+=sizeof(record);
 counters[c->kind-1]++;release(&lock);tls_set(tls_slot,0);
}
void heap_statistics(uint64_t*output){acquire(&lock);for(uint32_t i=0;i<8;i++)output[i]=counters[i];release(&lock);}
`, {heap_file: heapHandles, stack_file: heapHandles.add(8), tls_slot: heapHandles.add(16),
    lock: heapState, next_stack: heapState.add(8), used: heapState.add(12), counters: heapState.add(16),
    buffer: heapState.add(80), stacks: heapState.add(80 + 65520),
    write_file: k.getExportByName('WriteFile'), capture_context: native.capture_context,
    thread_id: k.getExportByName('GetCurrentThreadId'), tick_count: k.getExportByName('GetTickCount'),
    tls_get: k.getExportByName('TlsGetValue'), tls_set: k.getExportByName('TlsSetValue'),
    acquire: k.getExportByName('AcquireSRWLockExclusive'), release: k.getExportByName('ReleaseSRWLockExclusive')});
for (const [name, enter] of [['RtlAllocateHeap', 'heap_alloc_enter'], ['RtlFreeHeap', 'heap_free_enter'],
    ['RtlReAllocateHeap', 'heap_realloc_enter'], ['RtlDestroyHeap', 'heap_destroy_enter']]) {
    Interceptor.attach(nt.getExportByName(name), {onEnter: heapNative[enter], onLeave: heapNative.heap_leave});
}
const flushHeap = new NativeFunction(heapNative.flush_heap, 'void', []);
const heapStatistics = new NativeFunction(heapNative.heap_statistics, 'void', ['pointer']);
const heapStatsBuffer = Memory.alloc(64);
setInterval(flushHeap, 200);
setInterval(() => {
    heapStatistics(heapStatsBuffer);
    send({kind: 'heap-statistics', counts: Array.from({length: 8}, (_, i) => heapStatsBuffer.add(i * 8).readU64().toString())});
}, 1000);
rpc.exports.flush = () => { flushHeap(); };
send({kind: 'heap-ready', recordBytes: 48, stackRecordBytes: 816, minimumAllocationBytes: 0,
    stackHashBits: 128, stackHashSlots: 65536, maximumBufferedBytes: 65520});
