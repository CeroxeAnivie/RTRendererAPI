package top.ceroxe.rt.renderer.rt.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VulkanGpuSceneShaderSelfTest {
   private VulkanGpuSceneShaderSelfTest() {
   }

   public static void main(String[] arguments) {
      Map<String, Integer> stages = new LinkedHashMap<>();
      stages.put("gpuscene.rgen", 14);
      stages.put("gpuscene.rmiss", 17);
      stages.put("gpuscene.rchit", 16);
      stages.put("gpuscene.rahit", 15);
      long totalBytes = 0L;

      for(Map.Entry<String, Integer> stage : stages.entrySet()) {
         byte[] spirv = RtShaderModuleCompiler.compileForDiagnosticVerification("assets/rtrenderer/shaders/gpuscene/" + (String)stage.getKey(), (Integer)stage.getValue());
         require(spirv.length > 20 && (spirv.length & 3) == 0, (String)stage.getKey() + " did not produce aligned SPIR-V");
         totalBytes += (long)spirv.length;
      }

      byte[] hdrRaygen = RtShaderModuleCompiler.compileForVerification("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen", 14, false, true);
      require(hdrRaygen.length > 20 && (hdrRaygen.length & 3) == 0, "HDR ray generation did not produce aligned SPIR-V");
      totalBytes += (long)hdrRaygen.length;
      verifyForwardParityContract();
      verifyTemporalReconstructionContract();
      verifyTransmissionOpticsContract();
      verifyOpaqueMetallicReflectionContract();
      PrintStream output10000 = System.out;
      int size10001 = stages.size();
      output10000.println("VulkanGpuSceneShaderSelfTest passed: stages=" + size10001 + ", spirvBytes=" + totalBytes);
   }

   private static void verifyForwardParityContract() {
      String common = read("assets/rtrenderer/shaders/gpuscene/gpuscene_common.glsl");
      require(common.contains("gsInstanceLightmapCoordinate(uint instanceSlot)")
              && common.contains("GPU_SCENE_INSTANCE_PACKED_LIGHT_WORD")
              && common.contains("gsSampleLightmap(gsInstanceLightmapCoordinate(instanceSlot))"),
              "meshes without vertex lightmap coordinates must sample the instance fallback");
      require(common.contains("gsTriangleTextureFootprint") && common.contains("gsSampleTextureFootprint") && common.contains("gsSampleTextureLod"), "terrain texture sampling must expose one shared footprint/LOD path");
      require(common.contains("GPU_SCENE_FRAME_TEXTURE_MINIFICATION_MODE_WORD"), "frame texture filtering policy is not visible to the shader");
      require(common.contains("GPU_SCENE_FRAME_MAX_ANISOTROPY_WORD") && common.contains("major UV derivative"), "anisotropic filtering must use the bounded major-axis gather path");
      require(common.contains("gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.x))") && common.contains("gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.y))") && common.contains("gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.z))"), "forward parity must sample lightmap independently for all three vertices");
      require(common.contains("return c0 * barycentrics.x + c1 * barycentrics.y + c2 * barycentrics.z"), "forward parity must interpolate sampled vertex products");
      String closestHit = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rchit");
      String anyHit = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rahit");
      require(closestHit.contains("gsTriangleLightmapModulatedColor(")
              && closestHit.contains("barycentrics, gl_InstanceCustomIndexEXT")
              && anyHit.contains("gsTriangleLightmapModulatedColor(")
              && anyHit.contains("barycentrics, gl_InstanceCustomIndexEXT"),
              "closest-hit and any-hit must pass the instance slot to the shared lightmap path");
      require(closestHit.contains("gsSampleTextureFootprint") && anyHit.contains("gsSampleTextureFootprint"), "closest-hit and any-hit must share the same texture footprint sampler");
      String raygen = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen");
      require(raygen.contains("RTRENDERER_LINEAR_HDR_OUTPUT") && raygen.contains("rgba16f") && raygen.contains("accumulated += radiance"), "HDR output must retain untonemapped linear radiance in RGBA16F");
      require(raygen.contains("GPU_SCENE_FRAME_SAMPLE_COUNT_WORD") && raygen.contains("gsSubpixelOffset") && raygen.contains("sampleIndex < 8u"), "deterministic bounded anti-aliasing loop is missing");
      require(raygen.contains("else if (lightmapModulated)"), "forward parity must have an explicit ray-generation branch");
      require(raygen.contains("gsApplyDistanceFog(") && raygen.indexOf("else if (lightmapModulated)") < raygen.indexOf("gsEvaluatePbrSurface(primary, origin)"), "forward parity must bypass the renderer-owned PBR branch");
      require(raygen.contains("lightmapSampleCount") && raygen.contains("lightmapCoverage") && raygen.contains("gsLinearToSrgb(resolvedColor)") && raygen.contains("gsToneMap(resolvedColor)"), "forward parity coverage must blend display transforms after linear resolve");
   }

   private static void verifyTemporalReconstructionContract() {
      String raygen = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen");
      String closestHit = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rchit");
      require(raygen.contains("GPU_SCENE_BINDING_HISTORY_COLOR_INPUT") && raygen.contains("GPU_SCENE_BINDING_HISTORY_GEOMETRY_INPUT") && raygen.contains("GPU_SCENE_BINDING_MOTION_OUTPUT"), "temporal storage-image ABI is not consumed by ray generation");
      require(raygen.contains("gsPreviousPixelPosition") && raygen.contains("GPU_SCENE_FRAME_PREVIOUS_CAMERA_FORWARD_WORD") && raygen.contains("GPU_SCENE_FRAME_PREVIOUS_JITTER_WORD") && raygen.contains("GPU_SCENE_FRAME_CAMERA_DELTA_WORD"), "temporal reconstruction must reproject through the previous camera and jitter");
      require(raygen.contains("gsTemporalGeometryMatches") && raygen.contains("dot(currentNormal, previousNormal) >= 0.85") && raygen.contains("expectedPreviousLogDepth") && raygen.contains("depthTolerance"), "temporal reconstruction must reject normal and log-depth discontinuities");
      require(raygen.contains("GPU_SCENE_FLAG_DYNAMIC") && closestHit.contains("gsInstanceFlags(gl_InstanceCustomIndexEXT)"), "dynamic instances must not consume camera-only reprojection history");
      require(raygen.contains("GPU_SCENE_FRAME_MAX_HISTORY_FRAMES_WORD") && raygen.contains("nextHistoryAge") && raygen.contains("previousColor.a * historyConfidence") && raygen.indexOf("gsResolveTemporal") < raygen.indexOf("gsToneMap(resolvedColor)"), "linear temporal accumulation must be bounded and precede display encoding");
   }

   private static void verifyTransmissionOpticsContract() {
      String raygen = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen");
      String closestHit = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rchit");
      require(raygen.contains("GPU_SCENE_FRAME_MEDIUM_IOR_WORD") && raygen.contains("surfaceSample.transmissionIor.y"), "material and camera-medium IOR must both drive transmission");
      require(raygen.contains("refract(incidentDirection, normal, eta)") && raygen.contains("gsDielectricFresnel") && raygen.contains("return reflected;"), "transmission must implement Snell refraction, Fresnel, and total internal reflection");
      require(raygen.contains("GpuScenePayload reflectedSample = gsTraceRadiance") && raygen.contains("GpuScenePayload refractedSample = gsTraceRadiance"), "transmission must evaluate bounded reflected and refracted hardware rays");
      require(closestHit.contains("backFace ? GS_PAYLOAD_BACK_FACE") && raygen.contains("surfaceSample.state.y == GS_PAYLOAD_BACK_FACE"), "closest-hit orientation must reach the IOR transition calculation");
   }

   private static void verifyOpaqueMetallicReflectionContract() {
      String raygen = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen");
      require(raygen.contains("gsEvaluateOpaqueMetallicReflection") && raygen.contains("GpuScenePayload reflectedSample = gsTraceRadiance"), "opaque metallic shading must issue a bounded hardware reflection ray");
      require(raygen.contains("gsSampleGgxHalfVector") && raygen.contains("roughness * roughness") && raygen.contains("gsReflectionSample(sampleIndex, sampleCount, launchInfo)"), "opaque reflection direction must use deterministic roughness-aware GGX sampling");
      require(raygen.contains("f * N.L / pdf") && raygen.contains("gsFresnelSchlick(viewHalf") && raygen.contains("gsGeometrySmith(normalView, normalLight, roughness)"), "opaque reflection contribution must retain Fresnel and GGX visibility energy terms");
      require(raygen.contains("transmission <= 1.0e-4 && opacity >= 0.999") && raygen.contains("surface + opaqueReflection"), "metallic reflection must be isolated to opaque non-transmissive material evaluation");
   }

   private static String read(String path) {
      try {
         InputStream input = VulkanGpuSceneShaderSelfTest.class.getClassLoader().getResourceAsStream(path);

         String details2;
         try {
            if (input == null) {
               throw new AssertionError("missing shader resource " + path);
            }

            details2 = new String(input.readAllBytes(), StandardCharsets.UTF_8);
         } catch (Throwable value5) {
            if (input != null) {
               try {
                  input.close();
               } catch (Throwable value4) {
                  value5.addSuppressed(value4);
               }
            }

            throw value5;
         }

         if (input != null) {
            input.close();
         }

         return details2;
      } catch (IOException failure) {
         throw new AssertionError("failed to read shader resource " + path, failure);
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
