package top.ceroxe.rt.renderer.rt.pipeline;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.lwjgl.util.shaderc.Shaderc;

public final class RtPrecompiledShaderSelfTest {
   private static final String SHADER_ROOT = "assets/rtrenderer/shaders/";
   private static final List<String> SCENE_RAYGENS = List.of(
           "gpuscene/gpuscene.rgen", "gpuscene/gpuscene_surface.rgen", "gpuscene/gpuscene_compact.rgen",
           "gpuscene/gpuscene_compact_passive.rgen", "gpuscene/gpuscene_passive.rgen",
           "gpuscene/gpuscene_compact_passive_no_dielectric.rgen", "gpuscene/gpuscene_passive_no_dielectric.rgen",
           "gpuscene/gpuscene_primary_trace.rgen", "gpuscene/gpuscene_native_medium_trace.rgen",
           "gpuscene/gpuscene_native_scattering_lights.rgen",
           "gpuscene/gpuscene_native_scattering_query.rgen",
           "gpuscene/gpuscene_native_multiple_scattering.rgen", "gpuscene/gpuscene_native_density_scattering_lights.rgen",
           "gpuscene/gpuscene_native_optical_trace.rgen", "gpuscene/gpuscene_native_boundary_packet.rgen",
           "gpuscene/gpuscene_native_boundary_scattering.rgen",
           "gpuscene/gpuscene_native_pbr_medium_lights.rgen",
           "gpuscene/gpuscene_native_pbr_medium_query.rgen",
           "gpuscene/gpuscene_surface_primary_shade.rgen",
           "gpuscene/gpuscene_compact_primary_shade.rgen", "gpuscene/gpuscene_primary_shade.rgen",
           "gpuscene/gpuscene_compact_volume.rgen", "gpuscene/gpuscene_volume.rgen",
           "gpuscene/gpuscene_compact_caustics.rgen", "gpuscene/gpuscene_caustics.rgen", "gpuscene/gpuscene_photons.rgen");
   private static final Map<String, Integer> STAGES = stages();

   private RtPrecompiledShaderSelfTest() {
   }

   public static void main(String[] arguments) throws IOException {
      if (arguments.length == 6 && arguments[0].equals("--worker")) {
         String stage = arguments[1];
         Integer kind = STAGES.get(stage);
         if (kind == null) throw new IllegalArgumentException("unknown shader stage " + stage);
         byte[] spirv = RtShaderModuleCompiler.compileForVerification(SHADER_ROOT + stage, kind,
                 Boolean.parseBoolean(arguments[2]), Boolean.parseBoolean(arguments[3]), Boolean.parseBoolean(arguments[4]));
         Files.write(Path.of(arguments[5]), spirv);
      } else if (arguments.length >= 1 && arguments.length <= 3) {
         generate(Path.of(arguments[0]), arguments.length>1?arguments[1]:null, arguments.length>2?arguments[2]:null);
      } else if (arguments.length != 0) {
         throw new IllegalArgumentException("expected no arguments or output directory [stage [variant]]");
      } else {
         verify();
      }
   }

   private static void verify() {
      long[] verified = new long[2];
      compile(variants(null, null), compiled -> {
         ShaderVariant shader = compiled.shader();
         String resource = SHADER_ROOT + "spv/" + shader.name() + "/" + shader.stage().replace('/', '_') + ".spv";
         byte[] actual;
         try (var input = RtPrecompiledShaderSelfTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing pinned shader: " + resource);
            actual = input.readAllBytes();
         } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
         }
         if (!Arrays.equals(compiled.spirv(), actual)) {
            throw new AssertionError("precompiled shader is stale: stage=" + shader.stage()
                    + ", variant=" + shader.name());
         }
         verified[0] = Math.addExact(verified[0], actual.length);
         verified[1]++;
      });

      System.out.println("RtPrecompiledShaderSelfTest passed: variants=" + verified[1]
              + ", spirvBytes=" + verified[0]);
   }

   private static void generate(Path outputRoot,String selectedStage,String selectedVariant) throws IOException {
      Path root = outputRoot.toAbsolutePath().normalize();
      List<ShaderVariant> shaders = variants(selectedStage, selectedVariant);
      compile(shaders, compiled -> {
         ShaderVariant shader = compiled.shader();
         Path directory = root.resolve(shader.name());
         try {
            Files.createDirectories(directory);
            writeGeneratedShader(directory.resolve(shader.stage().replace('/', '_') + ".spv"), compiled.spirv());
         } catch (IOException failure) {
            throw new java.io.UncheckedIOException("cannot save " + shader.stage() + " " + shader.name(), failure);
         }
         System.out.println("Generated " + shader.stage() + " " + shader.name() + " bytes=" + compiled.spirv().length);
      });

      System.out.println("Generated " + shaders.size() + " pinned RT SPIR-V variants under " + root);
   }

   private static void writeGeneratedShader(Path target, byte[] spirv) throws IOException {
      // Leave identical files untouched: Windows may have their contents memory-mapped.
      if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), spirv)) return;
      Path temporary = Files.createTempFile(target.getParent(), ".rtrenderer-shader-", ".tmp");
      try {
         Files.write(temporary, spirv);
         try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
         }
      } finally {
         Files.deleteIfExists(temporary);
      }
   }

   private record ShaderVariant(String stage, int kind, boolean gbuffer, boolean hdr, boolean ser) {
      String name() { return variant(gbuffer, hdr, ser); }
   }

   private record CompiledShader(ShaderVariant shader, byte[] spirv) { }

   private static List<ShaderVariant> variants(String selectedStage, String selectedVariant) {
      var variants = new ArrayList<ShaderVariant>();
      for (var stage : STAGES.entrySet()) {
         if (selectedStage != null && !selectedStage.equals(stage.getKey())) continue;
         for (boolean gbuffer : new boolean[] { false, true }) {
            for (boolean hdr : hdrVariants(stage.getKey())) {
               for (boolean ser : serVariants(stage.getKey())) {
                  var shader = new ShaderVariant(stage.getKey(), stage.getValue(), gbuffer, hdr, ser);
                  if (selectedVariant == null || selectedVariant.equals(shader.name())) variants.add(shader);
               }
            }
         }
      }
      if (variants.isEmpty())
         throw new IllegalArgumentException("no shader matched stage=" + selectedStage + ", variant=" + selectedVariant);
      return List.copyOf(variants);
   }

   /** Compile every distinct source/options identity; an unused G-buffer define creates no new identity. */
   private static void compile(List<ShaderVariant> variants, Consumer<CompiledShader> completedShader) {
      int processors = Runtime.getRuntime().availableProcessors();
      int requested = Integer.getInteger("top.ceroxe.rt.shader.compileWorkers", 8);
      if (requested <= 0) throw new IllegalArgumentException("shader.compileWorkers must be positive");
      int workers = Math.min(variants.size(), Math.min(processors, Math.min(requested, 8)));
      System.out.printf(java.util.Locale.ROOT,
              "RT compiler workers=%d, processors=%d, requestedLimit=%d%n",
              workers, processors, requested);
      Map<ShaderVariant, List<Integer>> groups = new LinkedHashMap<>();
      Map<String, Boolean> gbufferUse = new LinkedHashMap<>();
      for (int index = 0; index < variants.size(); index++) {
         ShaderVariant shader = variants.get(index);
         boolean gbuffer = shader.gbuffer() && gbufferUse.computeIfAbsent(shader.stage(),
                 stage -> RtShaderModuleCompiler.usesDiagnosticGBuffer(SHADER_ROOT + stage));
         var identity = new ShaderVariant(shader.stage(), shader.kind(), gbuffer, shader.hdr(), shader.ser());
         groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(index);
      }
      System.out.println("Distinct source/options compilations=" + groups.size() + ", packaged variants=" + variants.size());
      var executor = Executors.newFixedThreadPool(workers);
      var completions = new ExecutorCompletionService<CompiledShader>(executor);
      var pending = new ArrayList<Future<CompiledShader>>(variants.size());
      // Four ordinary optimized raygens fit the measured 9.5 GB process-tree budget.
      // Structured volume and leaf stages use a smaller lease and can fill spare CPU slots.
      var memoryGate = new Semaphore(8, true);
      try {
         for (var group : groups.entrySet()) {
            ShaderVariant shader = group.getKey();
            pending.add(completions.submit(() -> {
               int memoryLease = shader.stage().endsWith(".rgen") && !shader.stage().contains("surface")
                       && !shader.stage().contains("volume") && !shader.stage().contains("caustics") ? 2 : 1;
               memoryGate.acquire(memoryLease);
               try {
                  long started = System.nanoTime();
                  System.out.println("Compiling RT stage=" + shader.stage() + ", variant=" + shader.name());
                  try {
                     byte[] spirv = compileIsolated(shader);
                     System.out.printf(java.util.Locale.ROOT,
                             "Compiled RT stage=%s, variant=%s, seconds=%.3f, bytes=%d%n",
                             shader.stage(), shader.name(), (System.nanoTime() - started) / 1e9, spirv.length);
                     return new CompiledShader(shader, spirv);
                  } catch (RuntimeException | Error failure) {
                     throw new IllegalStateException("shader compilation failed: stage=" + shader.stage()
                             + ", variant=" + shader.name(), failure);
                  }
               } finally {
                  memoryGate.release(memoryLease);
               }
            }));
         }
         // Observe failures promptly and publish each finished identity immediately.
         int completedVariants = 0;
         for (int completed = 0; completed < groups.size(); completed++) {
            CompiledShader result = completions.take().get();
            for (int index : groups.get(result.shader())) {
               var expanded = new CompiledShader(variants.get(index), result.spirv());
               completedShader.accept(expanded);
               completedVariants++;
            }
            System.out.println("Compiled RT variants: " + completedVariants + "/" + variants.size());
         }
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException("interrupted while compiling RT shader variants", interrupted);
      } catch (ExecutionException failed) {
         throw new IllegalStateException("offline RT shader compilation failed", failed.getCause());
      } finally {
         for (Future<CompiledShader> task : pending) task.cancel(true);
         executor.shutdownNow();
      }
   }

   private static byte[] compileIsolated(ShaderVariant shader) {
      Path output = null;
      Process child = null;
      try {
         output = Files.createTempFile("rtrenderer-shader-", ".spv");
         String executable = Path.of(System.getProperty("java.home"), "bin",
                 System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
         child = new ProcessBuilder(executable, "-Xmx96m", "-XX:+UseSerialGC", "-XX:-UseJVMCICompiler",
                 "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                 "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                 RtPrecompiledShaderSelfTest.class.getName(), "--worker", shader.stage(),
                 Boolean.toString(shader.gbuffer()), Boolean.toString(shader.hdr()), Boolean.toString(shader.ser()),
                 output.toString()).inheritIO().start();
         int exit = child.waitFor();
         if (exit != 0) throw new IllegalStateException("shader worker exited with " + exit);
         return Files.readAllBytes(output);
      } catch (IOException failure) {
         throw new java.io.UncheckedIOException(failure);
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException("shader worker interrupted", interrupted);
      } finally {
         if (child != null && child.isAlive()) child.destroyForcibly();
         if (output != null) {
            try { Files.deleteIfExists(output); }
            catch (IOException failure) { output.toFile().deleteOnExit(); }
         }
      }
   }

   private static Map<String, Integer> stages() {
      LinkedHashMap<String, Integer> stages = new LinkedHashMap<>();
      stages.put("bootstrap.rgen", 14);
      stages.put("bootstrap.rmiss", 17);
      stages.put("bootstrap.rchit", 16);
      stages.put("bootstrap.rahit", 15);
      for (String raygen : SCENE_RAYGENS) stages.put(raygen, Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene/gpuscene.rmiss", 17);
      stages.put("gpuscene/gpuscene.rchit", 16);
      stages.put("gpuscene/gpuscene.rahit", 15);
      stages.put("gpuscene/gpuscene_visibility.rmiss", Shaderc.shaderc_miss_shader);
      stages.put("gpuscene/gpuscene_visibility.rchit", Shaderc.shaderc_closesthit_shader);
      stages.put("gpuscene/gpuscene_visibility.rahit", Shaderc.shaderc_anyhit_shader);
      stages.put("gpuscene/gpuscene_native_boundary_packet.rahit", Shaderc.shaderc_anyhit_shader);
      stages.put("gpuscene/gpuscene_native_boundary_scattering.rahit", Shaderc.shaderc_anyhit_shader);
      stages.put("gpuscene/gpuscene_nrd_compose.comp", Shaderc.shaderc_compute_shader);
      stages.put("gpuscene/gpuscene_reconstruction_publish.comp", Shaderc.shaderc_compute_shader);
      stages.put("gpuscene/generic_frame_composition.comp", Shaderc.shaderc_compute_shader);
      return Collections.unmodifiableMap(stages);
   }

   private static boolean[] hdrVariants(String stage) {
      return SCENE_RAYGENS.contains(stage)
              || "gpuscene/gpuscene_nrd_compose.comp".equals(stage)
              || "gpuscene/generic_frame_composition.comp".equals(stage)
              ? new boolean[]{false, true} : new boolean[]{false};
   }

   private static boolean[] serVariants(String stage) {
      return SCENE_RAYGENS.contains(stage)
              ? new boolean[]{false, true} : new boolean[]{false};
   }

   private static String variant(boolean gbuffer, boolean hdr, boolean ser) {
      return (gbuffer ? "gbuffer" : "base") + (hdr ? "-hdr" : "") + (ser ? "-ser" : "");
   }
}
