package consumer;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.*;
import top.ceroxe.rt.renderer.spi.RendererBackendProvider;
/** Checks the published runtime closure without device initialization. */
public final class PublishedApiRuntimeSelfTest {
    private PublishedApiRuntimeSelfTest() { }
    public static void main(String[] args) throws Exception {
        var providers=ServiceLoader.load(RendererBackendProvider.class).stream().toList();
        if(providers.size()!=1||providers.getFirst().type()!=RtxptBackendProvider.class)
            throw new AssertionError("Published provider closure is not RTXPT-only");
        var jar=Path.of(RtxptRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try(var zip=new ZipFile(jar.toFile())) {
            String prefix="META-INF/native/windows-x86_64/";
            var archive=zip.getEntry(prefix+"rtxpt-runtime.zip");
            var digest=zip.getEntry(prefix+"rtxpt-runtime.sha256");
            if(archive==null||digest==null)throw new AssertionError("Missing embedded runtime");
            String expected=new String(zip.getInputStream(digest).readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII).trim();
            var hash=MessageDigest.getInstance("SHA-256");
            try(var input=zip.getInputStream(archive)) { byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))>=0)hash.update(buffer,0,n); }
            if(!HexFormat.of().formatHex(hash.digest()).equals(expected))throw new AssertionError("Embedded runtime digest mismatch");
        }
        if(!Files.isRegularFile(RtxptRuntime.jvmHostExecutable()))throw new AssertionError("Missing extracted host");
        if(RenderFrameRequest.builder(1,64,64,CameraState.lookAt(0,0,3,0,0,0).build()).build().width()!=64)
            throw new AssertionError("Published frame contract failed");
        System.out.println("PUBLISHED_RUNTIME PASS provider=RTXPT hashVerified=true extractedHost=true noGpu=true");
    }
}
