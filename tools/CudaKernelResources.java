import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import static java.lang.foreign.ValueLayout.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPbrMediumSource;

/** Offline launch-resource inspection against a frozen core JAR; no frame rendering. */
public final class CudaKernelResources {
    private static final Linker LINKER=Linker.nativeLinker();
    private static SymbolLookup driver;

    private static MethodHandle function(String name,java.lang.foreign.MemoryLayout... arguments) {
        return LINKER.downcallHandle(driver.find(name).orElseThrow(),FunctionDescriptor.of(JAVA_INT,arguments));
    }
    private static void call(MethodHandle function,Object... arguments) throws Throwable {
        int status=(int)function.invokeWithArguments(arguments);
        if(status!=0)throw new IllegalStateException("CUDA inspection status="+status);
    }
    private static MemorySegment handle(Object object,String name) throws ReflectiveOperationException {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);
        return (MemorySegment)field.get(object);
    }
    public static void main(String[] arguments) throws Throwable {
        try(Arena arena=Arena.ofConfined();var runtime=CudaMediumRuntime.open(0)) {
            driver=SymbolLookup.libraryLookup("nvcuda.dll",arena);
            var push=function("cuCtxPushCurrent_v2",ADDRESS);
            var pop=function("cuCtxPopCurrent_v2",ADDRESS);
            var attribute=function("cuFuncGetAttribute",ADDRESS,JAVA_INT,ADDRESS);
            var potential=function("cuOccupancyMaxPotentialBlockSize",ADDRESS,ADDRESS,ADDRESS,ADDRESS,JAVA_LONG,JAVA_INT);
            var active=function("cuOccupancyMaxActiveBlocksPerMultiprocessor",ADDRESS,ADDRESS,JAVA_INT,JAVA_LONG);
            var rows=new ArrayList<String>();
            for(String entry:arguments)try(var kernel=runtime.compileBatchKernel(CudaPbrMediumSource.source(),entry)) {
                var context=handle(runtime,"context");var function=handle(kernel,"function");
                call(push,context);
                try {
                    var output=arena.allocate(JAVA_INT);int[] attributes=new int[5];
                    for(int i=0;i<attributes.length;i++) {
                        call(attribute,output,i,function);attributes[i]=output.get(JAVA_INT,0);
                    }
                    var grid=arena.allocate(JAVA_INT);var block=arena.allocate(JAVA_INT);
                    call(potential,grid,block,function,MemorySegment.NULL,0L,0);
                    var occupancy=new ArrayList<String>();
                    for(int threads:new int[]{32,64,128,256,512,1024})if(threads<=attributes[0]) {
                        call(active,output,function,threads,0L);int blocks=output.get(JAVA_INT,0);
                        occupancy.add("{\"blockThreads\":"+threads+",\"activeBlocksPerSM\":"+blocks
                                +",\"activeThreadsPerSM\":"+(threads*blocks)+"}");
                    }
                    rows.add("{\"entry\":\""+entry+"\",\"maxBlockThreads\":"+attributes[0]
                            +",\"staticSharedBytes\":"+attributes[1]+",\"constantBytes\":"+attributes[2]
                            +",\"localBytesPerThread\":"+attributes[3]+",\"registersPerThread\":"+attributes[4]
                            +",\"suggestedBlockThreads\":"+block.get(JAVA_INT,0)
                            +",\"suggestedMinGridBlocks\":"+grid.get(JAVA_INT,0)
                            +",\"occupancy\":["+String.join(",",occupancy)+"]}");
                } finally { call(pop,arena.allocate(ADDRESS)); }
            }
            System.out.println("{\"device\":\""+runtime.deviceName()+"\",\"kernels\":["+String.join(",",rows)+"]}");
        }
    }
}
