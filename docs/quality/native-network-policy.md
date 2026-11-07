# Native network policy

## Confirmed causes

The observed formal host PID 43040, DLL SHA256
5cdb86fe1854a976f874c17cfbc5b7cd40a7b95f394f86ef71e423aa48a7182e,
listened on TCP [::]:8086 and owned an IPv4 wildcard UDP endpoint. Its native
cache enabled TRACY_ENABLE and TRACY_ON_DEMAND, with TRACY_ONLY_LOCALHOST and
TRACY_NO_BROADCAST disabled.

The pinned Tracy source starts its TCP listener at 8086 (trying through 8105)
and sends discovery broadcasts to 255.255.255.255. ON_DEMAND waits for a viewer
to capture; it does not stop sockets. This is a sufficient cause of an inbound
Windows Firewall prompt, not evidence of Internet data upload or a memory-crash
cause. The exact sources are External/CMakeLists.txt:54-55,
native/_deps/tracy-src/public/client/TracyProfiler.cpp:1783,1881-1926 and
public/common/TracySocket.cpp:436-487.

RtxptRuntimeBundle.java extracts the runtime under an archive-digest directory,
and RtxptRuntime.java selects that directory's rtrenderer_jvm_host.exe. Runtime
updates therefore change the executable path. Permission associated with an
older path is not a substitute for removing unnecessary listeners. Historical
Windows firewall rules were not inspected.

## Implemented candidate

1. Bridge builds use Tracy's official TRACY_ENABLE:BOOL=OFF. The cache comparison
   checks OFF, while core-only builds restore the original ON default. No Tracy
   source patch is used. API boundary/CSV/HUD diagnostics remain independent.
2. The Java bridge decides before native creation whether the session needs
   Streamline. Beauty and independent DLSS-RR keep it; independent lighting
   with reconstruction NONE does not initialize it. Session's owner-thread RAII
   policy spans Application initialization, render and shutdown.
3. Java no longer eagerly loads sl.interposer.dll. The API DLL uses the standard
   MSVC /DELAYLOAD:sl.interposer.dll plus delayimp, without delay-load hooks.
   Required sessions retain Donut's original signature check and LoadLibraryW.
4. Skipping slInit alone is insufficient: the actual old DLL import table also
   imports CreateDXGIFactory2, DXGIGetDebugInterface1, D3D12GetDebugInterface and
   D3D12CreateDevice from sl.interposer.dll. The network-only DeviceManager_DX12
   variant selects the true System32 dxgi/d3d12 entry points for SL-free sessions.
   Required sessions execute the original interposer calls. This selection is
   limited to device creation/debug entry points; no rendering algorithm changes.
5. The StreamlineIntegration variant skips unused SDK initialization, clears
   stale availability, and safely returns from device/adapter/frame/shutdown,
   interface-upgrade and input-marker calls while uninitialized. The original
   Sample sends ReflexTriggerFlash on every left mouse click without checking
   support, so that entry also requires a guard. The bridge only retains DLSS
   shutdown modules when the session actually uses Streamline.
6. Before slInit, the enabled-session variant clears eAllowOTA and
   eLoadDownloadedPlugins. Local DLSS, FG, RR, feature selection and quality
   defaults remain available. These flags alone do NOT guarantee fully offline
   beauty sessions, as explained below.

Independent lighting can request DLSS_RR before its first submit. Turning it on
later in an already SL-free live session now raises a clear error requesting a
renderer reopen, rather than silently pretending reconstruction is active.
The private legacy native create entry remains enabled for existing native
proofs; the production renderer uses the explicit createSession entry.

## Exact authorized source scope

There are two authorized exceptions (progress UI and network policy), spanning
three hash-locked official-file variants. The official export stays read-only;
all selected variants are written under the native build's rtrenderer-patches.
No upstream rendering, lighting, sampling, denoising algorithm or shader is patched.

| Exception | Official file | Original SHA256 | Build-side variant SHA256 | Insertions/hunks |
| --- | --- | --- | --- | --- |
| Progress UI | Rtxpt/SampleCommon/SampleCommon.cpp | fe2d1d29021ca6506707a3ea8749ffa37c6d33491fc321e045a16a84bc3742e0 | 7bb44fb7b58b7cb6d846ba8c71a69a0de08a5cf31caf26669f3f7f1a3fd30e01 | 1 |
| Network | External/Donut/src/app/streamline/StreamlineIntegration.cpp | 60b6756523e5e16a4d08e5b9842a99050048af1340008fbdf338c92377299929 | 7dfbab89cf5fabf480f7fe371af06ba45940dbba70a2cc76c0834632a86f8d1f | 10 |
| Network | External/Donut/src/app/dx12/DeviceManager_DX12.cpp | 039d933d45ae5fe83a1e67e77fdf826198528f6061a8fcd3258a50359d395193 | 1631af3e9cade40a68a78f1f309a1a4e5d350f8aca75b4b717db2f0611289b60 | 1 |

The two network JSON insertion contracts and unified patches are under
tools/patches/streamline-offline-*. RtxptOfflinePatch.cmake locks their hashes,
requires unique reversible anchors, verifies exact variant output bytes and
replaces exactly one source entry for each file. Donut's actual SOURCES entries
are relative; they are resolved against target SOURCE_DIR. An already-selected
variant is accepted for idempotence, still with exactly one replacement.

Assert-RtxptAuthorizedPatches.ps1 verifies all three source/patch identities,
generated bytes, target counts and exactly three manifests for the two allowed
exceptions. Assert-RtxptUpstream.ps1 remains independent and unchanged: it still
rejects modified official files or unexpected additions in the export.

Build-RtxptUpstream.ps1 no longer treats CMakeCache.txt as proof of success. Cache
reuse requires an audited-success receipt with matching hook/CMake/patch input
hashes, matching configured options and valid generated manifests. A failed
configure removes the receipt; it is recreated only after successful configure
and both original/variant audits. This repairs the observed failed-config cache
reuse, not just the initial relative-path replacement failure.

## Mandatory-update limit for required Streamline sessions

The pinned sl_core_types.h defaults include both OTA flags. Clearing them is a
valid opt-out for optional updates and downloaded-plugin selection, but the
pinned pluginManager.cpp:967-968 still calls checkForOTA with false. The
ota.cpp:889-921 path still invokes NGX Updater for mandatory updates; false only
omits the optional request. pluginManager.cpp:995-997 also honors a driver-profile
OTA override independently of the downloaded-plugin flag. Donut uses supplied
SDK binaries, so editing nearby Streamline source without rebuilding the actual
component would not change that behavior.

Consequently the candidate isolates sessions which do not need Streamline;
it does not promise complete offline behavior for beauty/FG/RR sessions. The
manifest deliberately retains guaranteesNoNetwork=false for that wider claim.
The sample scripts/sl.common.json key enableOTA=false has no discovered reader
in the locked source, and is not used as a fake fix.

## Validation and remaining runtime checks

- The old DLL's actual import table was inspected, including all 15 sl.interposer
  imports. API-owned direct slGetFeatureFunction calls are only in the optional
  shutdown-module retention guard. Other SL operations use initialization or
  feature-availability guards; the formerly unguarded frame/input/upgrade entries
  were explicitly covered by this network variant.
- Standalone cmake -P tests exercised the real relative source forms and repeated
  variant selection for both Donut files. Three generated manifests and all exact
  output bytes passed the source/variant audit. This is not native compilation.
- PowerShell parsing and git diff --check passed. The previous 4,426-original-file
  audit was also confirmed by the main build owner. The source export remains
  unchanged; final native compilation and runtime checks are owned by the main
  thread. This subtask did not compile, publish, replace DLL/PDB or launch graphics.
- Intermediate native-build success before the final device-entry variant does
  not validate the final candidate. Run the final incremental build and package
  identity checks once all production inputs are frozen.

For a newly started SL-free formal process, observe actual loaded modules after
initialization, during fresh output and after renderer close/reopen: no
sl.interposer.dll or sl.* feature module should be loaded. Inspect the new PE's
normal and delay import directories; SL must be a delay import. Check the actual
host PID for Tracy TCP listeners 8086-8105 and discovery UDP endpoints, while
confirming boundary/CSV/HUD diagnostics still work. Preserve any failure evidence.
The absence of SL/Tracy does not by itself prove the host's unrelated Java/game
networking is disabled.

Separately validate a required beauty/FG/RR session with the same feature settings
and local binaries. The signed interposer must load, feature availability must
remain real, and visuals/correctness must remain unchanged. Do not label the
beauty path fully offline solely because optional OTA flags are cleared.
