# Procedural ray tracing

Version 4.1.0 adds explicit procedural BLAS construction without changing the triangle command.
Use `AccelerationStructureAabbGeometry` with an `ACCELERATION_STRUCTURE_BUILD_INPUT` buffer
slice. Each primitive contains six little-endian float32 values, ordered minX/minY/minZ then
maxX/maxY/maxZ. Supply finite values with each minimum no greater than its maximum.
The slice offset and stride must be multiples of eight; stride must be at least 24 and fit
an unsigned 32-bit value. The slice must include the last primitive's complete 24-byte bounds,
but need not include padding after that primitive. Primitive count must be positive.

Submit `BuildProceduralBottomLevelAccelerationStructureCommand(destination, mode, geometries)`
outside a render pass. Geometry order defines geometry indices and shader binding table selection.
All entries are AABBs: Vulkan does not permit mixing triangles and AABBs in one BLAS. A TLAS may
reference separate triangle and procedural BLAS objects. Use `RayTracingShaderGroup.procedural`
with an intersection shader; that shader reports actual intersections inside the supplied bounds.
Non-opaque geometry permits any-hit execution unless overridden by instance or ray flags.

For example, this fragment creates one updatable procedural BLAS. The application supplies
unique resource IDs and a monotonically increasing `nextCommandSequence`, publishes `boundsBuffer`
through `submitResources` first, and checks its admission evidence before submitting `buildCommands`.
All named API types below are in `top.ceroxe.rt.renderer.api`; collection and byte-buffer types
come from `java.util` and `java.nio`.

```java
BufferResource boundsBuffer = new BufferResource(
        new RenderResourceId(200), ResourceVersion.initial(), 24,
        Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT));
ResourceSlice.BufferSlice bounds = new ResourceSlice.BufferSlice(boundsBuffer, new ByteRange(0, 24));
ByteBuffer bytes = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN);
bytes.putFloat(-1).putFloat(-1).putFloat(-1);
bytes.putFloat(1).putFloat(1).putFloat(1).flip();

AccelerationStructureResource blas = new AccelerationStructureResource(
        new RenderResourceId(201), ResourceVersion.initial(), AccelerationStructureKind.BOTTOM_LEVEL, true);
AccelerationStructureAabbGeometry geometry = new AccelerationStructureAabbGeometry(bounds, 24, 1, false);
RenderCommandTransaction buildCommands = RenderCommandTransaction.builder(nextCommandSequence)
        .add(new WriteBufferCommand(bounds, new ResourceData(bytes)))
        .add(new BuildProceduralBottomLevelAccelerationStructureCommand(
                blas, AccelerationStructureBuildMode.BUILD, List.of(geometry)))
        .build();
```

Create a TLAS instance referencing `blas` and bind it through the existing acceleration-structure
descriptor. The matching SBT entry is `RayTracingShaderGroup.procedural(closestHit, anyHit, intersection)`;
`intersection` must have stage `RAY_INTERSECTION`. Either hit shader may be absent. AABB overlap only
invokes intersection processing: the intersection shader must report a hit at the correct distance
and supply the hit attributes consumed by the hit shaders. See the complete
[command and shader sequence](Generic-Commands-and-Ray-Tracing.md).

The provider resolves exact buffer generations, records visibility from prior writes, and retains
the inputs until the GPU fence completes. `RECORDED` is admission, not completion. Observe
`GPU_COMPLETED` for a build or `OUTPUT_PRODUCED` for trace output before consuming its result.
Uploaded data remains an application contract; the provider cannot inspect GPU-generated bounds
at command admission. No CPU readback is inserted to guess or repair malformed bounds.

UPDATE requires an existing, completed destination declared with `allowUpdate=true`. Geometry
type, count, primitive count and opaque flags must match the original BUILD. Bounds and source
buffers may change. Rebuild or update affected TLAS objects before tracing changed BLAS bounds.
The same ownership rules as triangle geometry apply: retire every referencing TLAS before the
BLAS, and do not destroy in-flight build inputs. Failure does not produce success evidence.

The native acceptance task `:renderer-core:vulkanGenericProceduralRayTracingNativeSelfTest`
covers padded strides and nonzero offsets, multiple geometries, intersection attributes,
any-hit rejection, exact RGBA pixels before and after UPDATE, incompatible UPDATE rejection,
and TLAS/BLAS retirement with Vulkan validation enabled.

Published coordinates are `top.ceroxe.rt:renderer-api:4.1.0`; the runtime core and NVIDIA modules
resolve transitively at the same version. The [release validation record](quality/4.1.0-procedural-validation.md)
identifies the actual device, acceptance commands, signed bundle and Central publication checks.
