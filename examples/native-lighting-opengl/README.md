# OpenGL base plus native RTXPT lighting

`NativeLightingGlExample` is a host-independent example: OpenGL draws a sharp 640×360 raster base, RTXPT traces a separate 96×54 receiver grid and returns direct gain plus independent one-bounce GI, and OpenGL combines them on the GPU. The host alone maps HDR to the display and presents its window. No host G-buffer or second Vulkan renderer is created.

The default run creates a hidden GL context and accepts eight real frames. `--visible` is an explicit opt-in for viewing the example. `RtxptNativeLightingGlSelfTest` always stays hidden and uses one diagnostic readback after all frame work.

The example opens `RtxptRenderers.openNativeLighting(NativeLightingPlan.defaults(96,54))`; it does not require callers to assemble conflicting beauty/cache options. For expert tuning, derive `RtxptRenderers.nativeLightingConfiguration().copyBuilder()` and use the explicit configuration overload. The GL lease and synchronization duties below stay explicit. See [ordinary and expert use](../../docs/ordinary-and-expert.md).

With matching API/core/RTXPT artifacts from the same build and LWJGL 3.4.3 core, glfw, opengl and their Windows native JARs on `$releaseClasspath`:

```powershell
javac -encoding UTF-8 -cp $releaseClasspath -d build/gl-example examples/native-lighting-opengl/GlLightingPacketConsumer.java examples/native-lighting-opengl/NativeLightingGlExample.java
java -cp "build/gl-example;$releaseClasspath" top.ceroxe.rt.renderer.rtxpt.examples.NativeLightingGlExample
```

The native runtime's background-window option must be enabled for an unattended run (`RTR_RTXPT_BACKGROUND_WINDOW=1`). This does not change the host GL example's `--visible` choice.

## Interoperability contract

Create `GlLightingPacketConsumer` on the renderer owner thread with a current GL 4.5+ context. It requires `EXT_memory_object`, `EXT_memory_object_win32`, `EXT_semaphore` and `EXT_semaphore_win32`. Its eight-byte GL device LUID must match the packet's D3D12 adapter LUID; it rejects a different adapter before importing resources.

Each atomic packet imports every requested plane using `GL_HANDLE_TYPE_D3D12_RESOURCE_EXT`, dedicated memory, the advertised allocation size and zero offset. Formats map exactly to `RGBA16F`, `RGBA32F` and `RGBA32UI`. The shared fence is imported as `GL_HANDLE_TYPE_D3D12_FENCE_EXT`. Resource/fence handles stay owned by RTXPT; GL memory/semaphore objects are owned by the consumer. No Win32 handle is closed by the example.

The frame order is:

1. Set `GL_D3D12_FENCE_VALUE_EXT=readyValue`, then enqueue `glWaitSemaphoreEXT` for all planes in `GL_LAYOUT_GENERAL_EXT`.
2. Call `lease.markImported()`; enqueue GL reads/composition inside `consume`'s callback.
3. Set `completionValue`, enqueue `glSignalSemaphoreEXT`, then `glFlush()`.
4. Call `completionSignalEnqueued()` and close the Java lease. The producer observes the actual fence value before reusing its slot.

Normal consumption has no CPU readback, GPU-fence CPU wait, `glFinish` or full-frame CPU pixel loop. A callback exception still queues completion for previously submitted reads. A failed import leaves the lease unimported so it can be abandoned. A failed GPU completion signal is not reported as a successful return.

The consumer keeps at most three imported slot sets. On a new allocation generation for the same slot fence, the producer has already observed the old completion; the corresponding old GL textures, memory and semaphore are deleted, without evicting another slot's in-flight imports. One consumer belongs to one renderer lifetime. At shutdown, finish the final GL command stream once (`awaitCompletionForShutdown` is provided), close the GL consumer/imports, close the renderer, then destroy the GL context. The shutdown wait is outside the frame path.

## Color and host state

`compose` consumes a linear host texture and writes `hostRGB * mix(1,directRGB,directAlpha) + indirectRGB * indirectAlpha` to a linear host FBO. Receiver albedo is already included in the native indirect contribution and is not applied a second time. D3D12 signal rows are flipped into GL UV orientation. The example uses nearest signal sampling; it does not claim edge-aware or RR reconstruction. The host raster texture retains its full native pixel grid.

`compose` owns framebuffer, viewport, VAO, program, texture units 0–2 and relevant draw enables; restore your host state before subsequent UI/passes. For custom reconstruction/composition, use `consume(lease, packet -> ...)` and borrow the callback's GL texture names. Do not keep those names beyond the callback or submit later reads after the completion signal.

## Verification

```powershell
./gradlew.bat --no-daemon :renderer-rtxpt:rtxptNativeLightingGlSelfTest
```

The 2026-11-03 development-runtime run on RTX 5080 Laptop / NVIDIA GL 4.5 passed same-adapter import, eight real frames, 49×33→51×35 resize, all three formats, shared timeline waits/signals, native float normal/distance, integer validity and linear composition. One final readback measured maximum RGB error `0.0` against an exact `0.625` gain on a native one-pixel checkerboard. This establishes the tested HDR output direction and host composition, not the separate host-surface input direction or final packaged release acceptance. A follow-up four-grid resize test covers repeated generation retirement.
