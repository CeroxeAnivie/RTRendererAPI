package top.ceroxe.rt.renderer.api;

import java.nio.*;

/** Immutable patch payload and range validation; renderer/GPU rejection is tested separately. */
public final class NativeLightingApplicationPatchSelfTest {
    private static int checks;
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void reject(Runnable operation){checks++;try{operation.run();}catch(RuntimeException expected){return;}throw new AssertionError("Invalid application patch accepted");}
    public static void main(String[] arguments){
        var source=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);source.putInt(4,0x12345678).putInt(8,0x87654321);source.position(4).limit(12);
        var patch=new NativeLightingApplicationPatch(3,12,source);source.putInt(4,0);
        require(source.position()==4&&patch.data().remaining()==8,"Source cursor and exact remaining range");
        require(patch.data().getInt()==0x12345678&&patch.data().getInt(4)==0x87654321,"Immutable payload preserves byte order");
        var view=patch.data();view.position(8);require(patch.data().position()==0,"Accessor cursor is isolated");
        reject(()->patch.data().put(0,(byte)1));reject(()->new NativeLightingApplicationPatch(0,0,ByteBuffer.allocate(4)));
        reject(()->new NativeLightingApplicationPatch(1,-4,ByteBuffer.allocate(4)));
        reject(()->new NativeLightingApplicationPatch(1,2,ByteBuffer.allocate(4)));
        reject(()->new NativeLightingApplicationPatch(1,0,ByteBuffer.allocate(0)));
        reject(()->new NativeLightingApplicationPatch(1,0,ByteBuffer.allocate(3)));
        reject(()->new NativeLightingApplicationPatch(1,Integer.MAX_VALUE-3,ByteBuffer.allocate(8)));
        reject(()->new NativeLightingApplicationPatch(1,NativeLightingApplicationPatch.MAXIMUM_APPLICATION_BYTES,ByteBuffer.allocate(4)));
        reject(()->new NativeLightingApplicationPatch(1,0,null));
        System.out.println("NATIVE_LIGHTING_APPLICATION_PATCH_CPU PASS checks="+checks+" immutable=true alignedRange=true");
    }
}
