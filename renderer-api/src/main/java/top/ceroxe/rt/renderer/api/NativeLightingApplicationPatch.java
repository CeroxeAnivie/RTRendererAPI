package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable byte delta within an already published application-data capacity. */
public record NativeLightingApplicationPatch(long generation,int offset,ByteBuffer data) {
    public static final int MAXIMUM_APPLICATION_BYTES=16*1024*1024;
    public NativeLightingApplicationPatch {
        Objects.requireNonNull(data,"data");int bytes=data.remaining();
        if(generation<=0||offset<0||(offset&3)!=0||bytes<1||(bytes&3)!=0
            ||offset>MAXIMUM_APPLICATION_BYTES||bytes>MAXIMUM_APPLICATION_BYTES-offset)
            throw new IllegalArgumentException("Application patch requires a positive generation and a nonempty aligned range within 16 MiB");
        var order=data.order();var copy=ByteBuffer.allocate(bytes).order(order);copy.put(data.asReadOnlyBuffer()).flip();
        data=copy.asReadOnlyBuffer().order(order);
    }
    @Override public ByteBuffer data(){return data.asReadOnlyBuffer().order(data.order());}
}
