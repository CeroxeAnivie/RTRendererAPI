package top.ceroxe.rt.renderer.rt.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.util.shaderc.Shaderc;

public final class RtPrecompiledShaderSelfTest {
   private static final String SHADER_ROOT = "assets/rtrenderer/shaders/";
   private static final Map<String, Integer> STAGES = stages();

   private RtPrecompiledShaderSelfTest() {
   }

   public static void main(String[] arguments) throws IOException {
      if (arguments.length == 1) {
         generate(Path.of(arguments[0]));
      } else if (arguments.length != 0) {
         throw new IllegalArgumentException("expected no arguments or one SPIR-V output directory");
      } else {
         verify();
      }
   }

   private static void verify() {
      long verifiedBytes = 0L;
      int verifiedVariants = 0;

      for(Map.Entry<String, Integer> stage : STAGES.entrySet()) {
         for(boolean gbuffer : new boolean[]{false, true}) {
            for(boolean hdr : hdrVariants((String)stage.getKey())) {
               for(boolean ser : serVariants((String)stage.getKey())) {
                  byte[] expected = RtShaderModuleCompiler.compileForVerification("assets/rtrenderer/shaders/" + (String)stage.getKey(), (Integer)stage.getValue(), gbuffer, hdr, ser);
                  byte[] actual = RtShaderModuleCompiler.loadPrecompiled("assets/rtrenderer/shaders/" + (String)stage.getKey(), gbuffer, hdr, ser);
                  if (!Arrays.equals(expected, actual)) {
                     String details10002 = (String)stage.getKey();
                     throw new AssertionError("precompiled shader is stale: stage=" + details10002 + ", variant=" + variant(gbuffer, hdr, ser));
                  }

                  verifiedBytes = Math.addExact(verifiedBytes, (long)actual.length);
                  verifiedVariants++;
               }
            }
         }
      }

      System.out.println("RtPrecompiledShaderSelfTest passed: variants=" + verifiedVariants
              + ", spirvBytes=" + verifiedBytes);
   }

   private static void generate(Path outputRoot) throws IOException {
      Path root = outputRoot.toAbsolutePath().normalize();

      for(Map.Entry<String, Integer> stage : STAGES.entrySet()) {
         for(boolean gbuffer : new boolean[]{false, true}) {
            for(boolean hdr : hdrVariants((String)stage.getKey())) {
               for(boolean ser : serVariants((String)stage.getKey())) {
                  byte[] spirv = RtShaderModuleCompiler.compileForVerification("assets/rtrenderer/shaders/" + (String)stage.getKey(), (Integer)stage.getValue(), gbuffer, hdr, ser);
                  Path variant = root.resolve(variant(gbuffer, hdr, ser));
                  Files.createDirectories(variant);
                  String details10001 = (String)stage.getKey();
                  Files.write(variant.resolve(details10001.replace('/', '_') + ".spv"), spirv, new OpenOption[0]);
               }
            }
         }
      }

      System.out.println("Generated pinned RT SPIR-V resources under " + String.valueOf(root));
   }

   private static Map<String, Integer> stages() {
      LinkedHashMap<String, Integer> stages = new LinkedHashMap<>();
      stages.put("bootstrap.rgen", 14);
      stages.put("bootstrap.rmiss", 17);
      stages.put("bootstrap.rchit", 16);
      stages.put("bootstrap.rahit", 15);
      stages.put("gpuscene/gpuscene.rgen", 14);
      stages.put("gpuscene/gpuscene.rmiss", 17);
      stages.put("gpuscene/gpuscene.rchit", 16);
      stages.put("gpuscene/gpuscene.rahit", 15);
      stages.put("gpuscene/gpuscene_nrd_compose.comp", Shaderc.shaderc_compute_shader);
      stages.put("gpuscene/gpuscene_reconstruction_publish.comp", Shaderc.shaderc_compute_shader);
      stages.put("gpuscene/generic_frame_composition.comp", Shaderc.shaderc_compute_shader);
      return Map.copyOf(stages);
   }

   private static boolean[] hdrVariants(String stage) {
      return "gpuscene/gpuscene.rgen".equals(stage)
              || "gpuscene/gpuscene_nrd_compose.comp".equals(stage)
              || "gpuscene/generic_frame_composition.comp".equals(stage)
              ? new boolean[]{false, true} : new boolean[]{false};
   }

   private static String variant(boolean gbuffer, boolean hdr) {
      return variant(gbuffer, hdr, false);
   }

   private static boolean[] serVariants(String stage) {
      return "gpuscene/gpuscene.rgen".equals(stage)
              ? new boolean[]{false, true} : new boolean[]{false};
   }

   private static String variant(boolean gbuffer, boolean hdr, boolean ser) {
      return (gbuffer ? "gbuffer" : "base") + (hdr ? "-hdr" : "") + (ser ? "-ser" : "");
   }
}
