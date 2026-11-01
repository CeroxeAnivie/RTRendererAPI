package demo;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Reads user shortcuts only while this demo's own native window has focus. */
@SuppressWarnings("restricted") // Fixed Win32 signatures; launcher grants native access.
final class DemoWindowKeys {
    private static final MethodHandle FOREGROUND;
    private static final MethodHandle KEY_STATE;
    private static final int[] KEYS = {'1','2','3',0x1b};
    private static final String[] COMMANDS = {"mixed","glass","nested","quit"};
    static {
        var library = SymbolLookup.libraryLookup("user32.dll",Arena.global());
        var linker = Linker.nativeLinker();
        FOREGROUND = linker.downcallHandle(library.find("GetForegroundWindow").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS));
        KEY_STATE = linker.downcallHandle(library.find("GetAsyncKeyState").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_SHORT,ValueLayout.JAVA_INT));
    }
    private final long window;
    private int held;
    private long lastPoll;

    DemoWindowKeys(long window) { this.window=window; }

    String pollCommand() {
        long now=System.nanoTime();
        if(now-lastPoll<25_000_000L)return null;
        lastPoll=now;
        try {
            if(((MemorySegment)FOREGROUND.invokeExact()).address()!=window) { held=0;return null; }
            int down=0;
            for(int i=0;i<KEYS.length;i++)if(((short)KEY_STATE.invokeExact(KEYS[i])&0x8000)!=0)down|=1<<i;
            int pressed=down&~held;
            held=down;
            return pressed==0?null:COMMANDS[Integer.numberOfTrailingZeros(pressed)];
        } catch(Throwable failure) { throw new IllegalStateException("Demo window shortcut polling failed",failure); }
    }
}
