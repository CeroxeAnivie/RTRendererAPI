# Retained high-framerate resource updates

Version: 7.1.4. The user selected the best measured falloff-branch candidate and stopped further optimization. Delivery channels are GitHub and Maven Local; Central is excluded. This document does not declare all F01–F10 accepted.

The implementation separates native candidate coverage from geometry builds, preserves a stable TLAS, uses compatible clone/refit generations for deformations, reuses memory packets, transfers generation-checked texture rectangles, and bounds admission maintenance. Completed resource generations are released by a bounded worker after both producer queues retire.

New expert diagnostics are `RtxptAccess.resourceMaintenance()` and the independently callable `protocolMonitor()` snapshot. A protocol monitor belongs to one native session and expires when it is replaced. Diagnostic OFF suppresses normal protocol events; first-fault evidence remains available. A device-removal completion marker never advances a fence or resource generation.

The final lighting implementation retains fused dispatch plus real GI RR, near-first bitstack traversal, parent/dirty publication, all-dynamic-source shaders, early guide writes and common-exponent falloff branches. Regressing packed-order and concurrent-queue experiments were removed from implementation, SDK and packaging.

Existing targeted checks cover transport contracts, native alpha/coverage, local refit, metadata, protocol observation, fault latching, resource release and HDR comparisons. Passed programs and the old baseline are not rerun. The 977-mesh, 750,336-triangle, 22,000-source fixture measured 290.63 FPS over 30 seconds; update-frame full GPU P50/P95 are 5.010016/5.606112 ms. The update-frame 4 ms target remains unmet. Over 7 ms: 16/8,722 frames (0.1834%), all update frames; none exceeded 16.7 ms.

The full 719/977/4096 growth matrix and complete OFF/DEEP comparisons remain incomplete. This API fixture makes no MC acceptance or GL driver root-cause claim. Performance evidence retains its original runtime hashes; final archive checks do not relabel old measurements as a new final-binary GPU run. Results and limits are in docs/quality/high-framerate-resources.md; consumer instructions are in docs/consumer-agent-handoff.md.
