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
      verifyDirectionalDiffuseContract();
      verifyTemporalReconstructionContract();
      verifyTransmissionOpticsContract();
      verifyOpaqueMetallicReflectionContract();
      verifyNrdSignalContract();
      verifyReconstructionSignalContract();
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

   private static void verifyDirectionalDiffuseContract() {
      String common = read("assets/rtrenderer/shaders/gpuscene/gpuscene_common.glsl");
      String closestHit = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rchit");
      require(common.contains("gsInstanceDirectionalDiffuse(")
                  && common.contains("firstIntensity * max(dot(normal, firstDirection), 0.0)")
                  && common.contains("secondIntensity * max(dot(normal, secondDirection), 0.0)")
                  && common.contains("GPU_SCENE_INSTANCE_DIRECTIONAL_DIFFUSE_FLIP_BACK_FACE"),
            "directional diffuse must retain the two-direction Lambert and back-face contracts");
      require(closestHit.contains("objectShadingNormal = normalize(")
                  && closestHit.contains("worldShadingNormal = normalize(")
                  && closestHit.contains("objectShadingNormal = objectGeometricNormal")
                  && closestHit.contains("worldShadingNormal = geometricNormal")
                  && closestHit.contains("objectShadingNormal, worldShadingNormal, backFace")
                  && closestHit.indexOf("gsInstanceDirectionalDiffuse(")
                  < closestHit.indexOf("uint normalTexture ="),
            "directional diffuse must use interpolated/fallback normals before normal-map perturbation");
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

   private static void verifyNrdSignalContract() {
      String raygen = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen");
      String closestHit = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rchit");
      String miss = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rmiss");
      String compose = read("assets/rtrenderer/shaders/gpuscene/gpuscene_nrd_compose.comp");
      require(raygen.contains("GPU_SCENE_BINDING_DENOISING_NORMAL_ROUGHNESS")
              && raygen.contains("GPU_SCENE_BINDING_DENOISING_VIEW_Z")
              && raygen.contains("GPU_SCENE_BINDING_DENOISING_MOTION_VECTORS")
              && raygen.contains("GPU_SCENE_BINDING_DENOISING_DIFFUSE_RADIANCE_HIT_DISTANCE")
              && raygen.contains("GPU_SCENE_BINDING_DENOISING_SPECULAR_RADIANCE_HIT_DISTANCE")
              && raygen.contains("GPU_SCENE_BINDING_DENOISING_DIFFUSE_MATERIAL_FACTOR")
              && raygen.contains("GPU_SCENE_BINDING_DENOISING_SPECULAR_MATERIAL_FACTOR"),
              "ray generation must declare every NRD signal binding");
      require(raygen.contains("gsDenoisingActive()")
              && raygen.contains("GPU_SCENE_FRAME_FEATURE_FLAGS_WORD")
              && raygen.contains("GPU_SCENE_FEATURE_FLAG_DENOISING_ACTIVE"),
              "NRD image writes must be gated by explicit frame capability state");
      require(raygen.contains("GsRadianceSplit")
              && raygen.contains("surfaceSplit.diffuse")
              && raygen.contains("surfaceSplit.specular"),
              "NRD inputs must preserve the PBR diffuse/specular split");
      require(raygen.contains("gsNrdPackNormalRoughness")
              && raygen.contains("NRD_NORMAL_ENCODING=2")
              && raygen.contains("gsNrdNormalizedHitDistance")
              && raygen.contains("ReblurHitDistanceParameters{A=3, B=0.1, C=20}"),
              "NRD guide and hit-distance encodings must match the native REBLUR build defaults");
      String noJitterMotion = "previousPixelNoJitter - currentPixelNoJitter";
      require(raygen.contains("dot(sanitized, vec3(0.25, 0.5, 0.25))")
              && raygen.contains("gsPreviousPixelPositionNoJitter")
              && raygen.contains("gsCurrentPixelPositionNoJitter")
              && raygen.indexOf(noJitterMotion) != raygen.lastIndexOf(noJitterMotion)
              && raygen.contains("imageStore(denoisingDiffuseRadianceHitDistance")
              && raygen.contains("imageStore(denoisingSpecularRadianceHitDistance"),
              "NRD and Streamline must share dense previous-current motion from non-jittered projections");
      require(closestHit.contains("payload.previousWorldPosition = vec4(")
              && closestHit.contains("gsPreviousInstancePoint(gl_InstanceCustomIndexEXT, localPosition)")
              && closestHit.contains("payload.motionRevision = gsInstanceMotionRevision")
              && miss.contains("payload.previousWorldPosition = vec4(0.0)")
              && miss.contains("payload.motionRevision = uvec2(0u)")
              && raygen.contains("motionRevision > previousRevision && motionRevision <= currentRevision"),
              "instance reprojection payload and per-instance revision gate must be initialized end to end");
      require(raygen.contains("denoisingActive && sampleIndex == 0u")
              && raygen.contains("denoisingSplit.diffuse += surfaceSplit.diffuse * surfaceWeight")
              && raygen.contains("denoisingSplit.specular += (surfaceSplit.specular + opaqueReflection) * surfaceWeight")
              && raygen.contains("denoisingSplit.diffuse /= float(sampleCount) * denoisingDiffuseFactor")
              && raygen.contains("denoisingSplit.specular /= float(sampleCount) * denoisingSpecularFactor"),
              "NRD signal must average all SPP contributions with one stable material guide");
      require(raygen.contains("gsNrdMaterialFactors")
              && raygen.contains("previousViewZ - viewZ")
              && raygen.contains("GS_NRD_MISS_VIEW_Z")
              && raygen.contains("viewZ = denoisingPrimary")
              && !raygen.contains("denoisingHitDistance = max(distance"),
              "NRD must demodulate materials, mark sky outside its range, provide 2.5D motion, and exclude primary hitT");
      require(raygen.contains("if (!denoisingActive && historyValid && reprojected)"),
              "the renderer temporal resolve must not accumulate over NRD temporal history");
      require(compose.contains("vec3 resolved = max(trace + denoised - noisy, vec3(0.0))")
              && compose.contains("uniform readonly image2D traceImage")
              && compose.contains("uniform readonly image2D denoisingViewZ")
              && compose.contains("if (!(viewZ < 1.0e6))")
              && compose.contains("uniform readonly image2D diffuseMaterialFactor")
              && compose.contains("uniform readonly image2D specularMaterialFactor")
              && compose.contains("uniform writeonly image2D outputImage"),
              "NRD compose must replace only the noisy radiance component in the published image");
   }

   private static void verifyReconstructionSignalContract() {
      String raygen = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen");
      require(raygen.contains("GPU_SCENE_BINDING_RECONSTRUCTION_DEPTH")
                  && raygen.contains("GPU_SCENE_BINDING_RECONSTRUCTION_MOTION_VECTORS")
                  && raygen.contains("GPU_SCENE_BINDING_RECONSTRUCTION_EXPOSURE"),
              "ray generation must declare every reconstruction signal binding");
      require(raygen.contains("gsReconstructionActive()")
                  && raygen.contains("GPU_SCENE_FEATURE_FLAG_RECONSTRUCTION_ACTIVE"),
              "reconstruction writes must be gated by the resolved feature capability");
      require(raygen.contains("imageStore(reconstructionDepth")
                  && raygen.contains("imageStore(reconstructionMotionVectors")
                  && raygen.contains("imageStore(reconstructionExposure"),
              "reconstruction must produce depth, motion, and exposure rather than reuse temporal images");
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
