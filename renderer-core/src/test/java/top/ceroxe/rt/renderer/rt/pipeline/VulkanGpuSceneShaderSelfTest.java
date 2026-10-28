package top.ceroxe.rt.renderer.rt.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.lwjgl.util.shaderc.Shaderc;

public final class VulkanGpuSceneShaderSelfTest {
   private static final String ROOT = "assets/rtrenderer/shaders/gpuscene/";
   private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"\\s*$");
   private VulkanGpuSceneShaderSelfTest() {
   }

   public static void main(String[] arguments) {
      boolean precompiled = arguments.length == 1 && arguments[0].equals("--precompiled");
      if (arguments.length != 0 && !precompiled) throw new IllegalArgumentException("expected [--precompiled]");
      Map<String, Integer> stages = new LinkedHashMap<>();
      stages.put("gpuscene_surface.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene_compact.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene_compact_volume.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene_volume.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene_compact_caustics.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene_caustics.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene_photons.rgen", Shaderc.shaderc_raygen_shader);
      stages.put("gpuscene.rmiss", Shaderc.shaderc_miss_shader);
      stages.put("gpuscene.rchit", Shaderc.shaderc_closesthit_shader);
      stages.put("gpuscene.rahit", Shaderc.shaderc_anyhit_shader);
      stages.put("gpuscene_visibility.rmiss", Shaderc.shaderc_miss_shader);
      stages.put("gpuscene_visibility.rchit", Shaderc.shaderc_closesthit_shader);
      stages.put("gpuscene_visibility.rahit", Shaderc.shaderc_anyhit_shader);
      long totalBytes = 0L;

      for(Map.Entry<String, Integer> stage : stages.entrySet()) {
         System.out.println((precompiled ? "Checking pinned RT stage " : "Compiling RT stage ") + stage.getKey());
         byte[] spirv = precompiled
                 ? RtShaderModuleCompiler.loadPrecompiled(ROOT + stage.getKey(), true, false)
                 : RtShaderModuleCompiler.compileForDiagnosticVerification(ROOT + stage.getKey(), stage.getValue());
         verifySpirv(stage.getKey(), spirv);
         totalBytes += (long)spirv.length;
      }

      for (String raygen : new String[]{"gpuscene_surface.rgen", "gpuscene_compact.rgen", "gpuscene.rgen",
              "gpuscene_compact_volume.rgen", "gpuscene_volume.rgen",
              "gpuscene_compact_caustics.rgen", "gpuscene_caustics.rgen", "gpuscene_photons.rgen"}) {
         byte[] hdr = precompiled ? RtShaderModuleCompiler.loadPrecompiled(ROOT + raygen, false, true)
                 : RtShaderModuleCompiler.compileForVerification(ROOT + raygen, Shaderc.shaderc_raygen_shader, false, true);
         verifySpirv(raygen, hdr);
         totalBytes += hdr.length;
      }
      byte[] compose = precompiled ? RtShaderModuleCompiler.loadPrecompiled(ROOT + "gpuscene_nrd_compose.comp", false, true)
              : RtShaderModuleCompiler.compileForVerification(ROOT + "gpuscene_nrd_compose.comp", Shaderc.shaderc_compute_shader, false, true);
      verifySpirv("gpuscene_nrd_compose.comp", compose);
      totalBytes += compose.length;
      verifyForwardParityContract();
      verifyDirectionalDiffuseContract();
      verifyTemporalReconstructionContract();
      verifyTransmissionOpticsContract();
      verifyTransportLayeringContract();
      verifyOpaqueMetallicReflectionContract();
      verifyNrdSignalContract();
      verifyReconstructionSignalContract();
      System.out.println("VulkanGpuSceneShaderSelfTest passed: stages=" + stages.size() + ", HDR raygens=8, NRD compose=1, spirvBytes=" + totalBytes);
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
      require(raygen.contains("else if (lightmapModulated && primary.transmissionIor.x<=0.0"), "forward parity must have an explicit ray-generation branch");
      int forwardBranch = raygen.indexOf("else if (lightmapModulated &&");
      int pbrBranch = raygen.indexOf("gsEvaluatePbrSurface(primary, origin)");
      require(raygen.contains("gsApplyDistanceFog(") && forwardBranch >= 0 && pbrBranch > forwardBranch,
              "forward parity must bypass the renderer-owned PBR branch");
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
      String transport = compact(read(ROOT + "gpuscene_transport.glsl"));
      String optics = compact(read(ROOT + "gpuscene_optics.glsl"));
      String runtime = compact(read(ROOT + "gpuscene_transport_runtime.glsl"));
      String closestHit = read(ROOT + "gpuscene.rchit");
      require(transport.contains("GPU_SCENE_FRAME_MEDIUM_IOR_WORD")
                      && transport.contains("gsInterfaceIors(") && transport.contains(".transmissionIor.y"),
              "material and camera-medium IOR must both drive transmission");
      require(optics.contains("gsRefractDirection(") && transport.contains("boolgsRefractDirection(")
                      && optics.contains("reflect(") && optics.contains("gsDielectricFresnel("),
              "transmission must use Snell refraction and dielectric Fresnel reflection");
      require(optics.contains("gsOpticalContinuation(") && optics.contains("gsTraceRadiance(")
                      && optics.contains("GsMediumPathcontinuationPath=incidentPath")
                      && optics.contains("continuationPath=transmittedPath")
                      && optics.contains("if(!reflection)") && optics.contains("uintbranches=split?2u:1u"),
              "reflected and refracted continuations must retain their respective medium paths");
      require(closestHit.contains("backFace ? GS_PAYLOAD_BACK_FACE")
                      && transport.contains("GS_PAYLOAD_BACK_FACE") && transport.contains("gsCrossBoundary("),
              "closest-hit orientation must reach boundary and material IOR transitions");
      require(runtime.contains("rayPayloadEXTGsVisibilityPayload")
                      && runtime.contains("GsVisibilityPayloadhit=visibility")
                      && runtime.contains("traceRayEXT(") && runtime.contains("integrationRemaining"),
              "ordered shadows must trace compact visibility events with a bounded integration budget");
      require(read(ROOT + "gpuscene_lighting.glsl")
                      .contains("inner == outer ? step(outer, cone) : smoothstep(outer, inner, cone)"),
              "spot lights must define deterministic equal-cone behavior");
   }

   private static void verifyTransportLayeringContract() {
      String surface = readRaw(ROOT + "gpuscene_surface.rgen");
      String compact = readRaw(ROOT + "gpuscene_compact.rgen");
      String transport = readRaw(ROOT + "gpuscene_transport.glsl");
      String visibility = readRaw(ROOT + "gpuscene_visibility.glsl");
      String runtime = readRaw(ROOT + "gpuscene_transport_runtime.glsl");
      require(runtime.contains("GPU_SCENE_FEATURE_FLAG_OPAQUE_VISIBILITY"),
              "binary visibility must require the scene's full opacity proof, including alpha coverage");
      require(surface.contains("RTRENDERER_SURFACE_ONLY") && compact.contains("RTRENDERER_COMPACT_MEDIA"),
              "surface and compact entry points must declare explicit compile-time specializations");
      String extended = readRaw(ROOT + "gpuscene_volume.rgen");
      String compactExtended = readRaw(ROOT + "gpuscene_compact_volume.rgen");
      require(extended.contains("RTRENDERER_VOLUME_TRANSPORT")
                      && compactExtended.contains("RTRENDERER_VOLUME_TRANSPORT")
                      && compactExtended.contains("RTRENDERER_COMPACT_MEDIA")
                      && !surface.contains("RTRENDERER_VOLUME_TRANSPORT")
                      && !compact.contains("RTRENDERER_VOLUME_TRANSPORT"),
              "heterogeneous/multiple-scattering programs must be isolated from ordinary specializations");
      String multiple = readRaw(ROOT + "gpuscene_multiscatter.glsl");
      require(multiple.contains("RTRENDERER_VOLUME_TRANSPORT")
                      && !multiple.contains("gsIntegrateMedium(")
                      && multiple.contains("gsDensityFlight(") && multiple.contains("gsSamplePhaseHg("),
              "multiple scattering must sample volume collisions without recursive integration");
      require(transport.contains("GS_MEDIUM_PATH_CAPACITY") && transport.contains("RTRENDERER_COMPACT_MEDIA")
                      && transport.contains("RTRENDERER_SURFACE_ONLY"),
              "medium state must specialize its capacity and disappear from the ordinary surface path");
      require(visibility.contains("struct GsVisibilityPayload")
                      && !visibility.contains("GpuScenePayload") && !visibility.contains("GsMediumPath")
                      && !visibility.contains("GsRadianceSplit") && !visibility.contains("gpuscene_lighting.glsl"),
              "visibility queries must not carry surface radiance, lighting services or medium stacks");
      for (String stage : new String[]{"gpuscene.rmiss", "gpuscene.rchit", "gpuscene.rahit",
              "gpuscene_visibility.rmiss", "gpuscene_visibility.rchit", "gpuscene_visibility.rahit"}) {
         String code = readRaw(ROOT + stage);
         require(!code.contains("gpuscene_transport_runtime.glsl") && !code.contains("gpuscene_optics.glsl"),
                 stage + " must remain a leaf hit/miss stage");
      }
   }

   private static void verifyOpaqueMetallicReflectionContract() {
      String raygen = read("assets/rtrenderer/shaders/gpuscene/gpuscene.rgen");
      require(raygen.contains("gsOpaqueMetallicReflectionWeight") && raygen.contains("gsEvaluateSecondaryTransport")
                      && raygen.contains("gsOpticalContinuation(") && raygen.contains("gsTraceRadiance("),
              "opaque metallic shading must issue bounded hardware reflection rays through the transport layer");
      require(raygen.contains("gsSampleGgxHalfVector") && raygen.contains("roughness * roughness") && raygen.contains("gsReflectionSample(sampleIndex, sampleCount, launchInfo)"), "opaque reflection direction must use deterministic roughness-aware GGX sampling");
      require(raygen.contains("f * N.L / pdf") && raygen.contains("gsFresnelSchlick(viewHalf") && raygen.contains("gsGeometrySmith(normalView, normalLight, roughness)"), "opaque reflection contribution must retain Fresnel and GGX visibility energy terms");
      require(raygen.contains("vec3 opaqueReflection = transmission == 0.0")
                      && raygen.contains("surface + opaqueReflection"),
              "metallic reflection must remain isolated to opaque non-transmissive materials");
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
      return read(path, new HashSet<>());
   }

   private static String read(String path, Set<String> active) {
      require(active.add(path), "shader include cycle: " + path);
      String source = readRaw(path);
      var matcher = INCLUDE.matcher(source);
      var expanded = new StringBuilder();
      int previous = 0;
      while (matcher.find()) {
         expanded.append(source, previous, matcher.start());
         expanded.append(read(path.substring(0, path.lastIndexOf('/') + 1) + matcher.group(1), active));
         expanded.append('\n');
         previous = matcher.end();
      }
      expanded.append(source, previous, source.length());
      active.remove(path);
      return expanded.toString();
   }

   private static String readRaw(String path) {
      try (InputStream input = VulkanGpuSceneShaderSelfTest.class.getClassLoader().getResourceAsStream(path)) {
         require(input != null, "missing shader resource " + path);
         return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException failure) {
         throw new AssertionError("failed to read shader resource " + path, failure);
      }
   }

   private static String compact(String code) { return code.replaceAll("\\s+", ""); }

   /** Checks actual compiled stages, including the absence of nested query-service traces. */
   private static void verifySpirv(String stage, byte[] code) {
      require(code.length > 20 && (code.length & 3) == 0, stage + " did not produce aligned SPIR-V");
      var words = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
      require(words.get(0) == 0x07230203 && words.get(3) > 0, stage + " has an invalid SPIR-V header");
      int expectedModel = stage.endsWith(".rgen") ? 5313 : stage.endsWith(".rahit") ? 5315
              : stage.endsWith(".rchit") ? 5316 : stage.endsWith(".rmiss") ? 5317 : 5;
      int entryPoints = 0, traces = 0, payloadVariables = 0;
      for (int offset = 5; offset < words.limit();) {
         int instruction = words.get(offset), count = instruction >>> 16, opcode = instruction & 0xffff;
         require(count > 0 && offset + count <= words.limit(), stage + " contains a truncated instruction");
         if (opcode == 15) { // OpEntryPoint
            require(count >= 4 && words.get(offset + 1) == expectedModel, stage + " has the wrong execution model");
            entryPoints++;
         }
         if (opcode == 4445) traces++; // OpTraceRayKHR
         if (opcode == 59 && count >= 4 && words.get(offset + 3) == 5338) payloadVariables++; // RayPayloadKHR OpVariable
         offset += count;
      }
      require(entryPoints == 1, stage + " must expose exactly one stage entry point");
      if (stage.endsWith(".rgen")) {
         require(traces > 0, stage + " must issue real hardware rays");
         int expectedPayloads = stage.equals("gpuscene_surface.rgen") ? 1 : 2;
         require(payloadVariables == expectedPayloads,
                 stage + " has unexpected payload coupling: " + payloadVariables + " instead of " + expectedPayloads);
      } else {
         require(traces == 0, stage + " must not recursively dispatch lighting or medium services");
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
