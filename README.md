# RTRendererAPI

RTRendererAPI is a standalone Java 25 Vulkan ray tracing renderer library. It
was extracted from MCVulkanRT without bringing Minecraft, Fabric, Mojang, mixin,
or OpenGL host dependencies into the renderer modules.

## Modules

- `renderer-api` contains the stable, host-independent renderer contracts. A
  host application should compile against this module.
- `renderer-core` implements those contracts with LWJGL, Vulkan RT, VMA,
  persistent scene resources, BLAS/TLAS ownership, and bounded frame resources.

The dependency direction is intentionally one-way:

```text
host application -> renderer-api <- renderer-core
```

The migrated source keeps its existing `top.ceroxe.mcvulkanrt.renderer`
namespace so this extraction does not introduce a risky API-wide package
rewrite. Maven publication coordinates are independent from Java package names.

## Requirements

- Java 25
- Windows with a Vulkan 1.2-capable driver
- Vulkan ray tracing extensions for hardware RT execution

CPU-only contract checks do not require a Vulkan RT device. Native hardware
gates are separate, explicit Gradle tasks.

## Build and verify

Use the checked-in Gradle Wrapper:

```powershell
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
.\gradlew.bat clean check assemble --no-daemon --console=plain
```

The default `check` task runs both modules' contract and architecture boundary
checks. Hardware-native gates are not silently included in normal API builds.

## Consume from Gradle

After publishing locally with `./gradlew publishToMavenLocal`, a host can depend
on the API and discover the core provider at runtime:

```groovy
dependencies {
    implementation 'top.ceroxe.rtrenderer:renderer-api:0.1.0'
    runtimeOnly 'top.ceroxe.rtrenderer:renderer-core:0.1.0'
}
```

For an isolated repository under this project instead of Maven Local:

```powershell
.\gradlew.bat publishAllToLocalStagingRepository --no-daemon --console=plain
```

Artifacts are written to `build/repository`.

## License

RTRendererAPI is available under the MIT License. See `LICENSE` for details.
