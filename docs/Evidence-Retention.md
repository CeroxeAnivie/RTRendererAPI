# Generic Evidence Retention

Version 4.0.2 bounds provider-owned execution history. It fixes the command and retired-generation
retention found in the published 4.0.1 implementation. Existing query method descriptors remain
unchanged. The optional `RendererEvidenceAccess` extension adds explicit missing-history results,
command evidence leases and immutable retention statistics.

## Configuration

`RendererConfig.expertBuilder().evidenceRetention(policy)` selects renderer-lifetime budgets.
Preset configurations use `EvidenceRetentionPolicy.bounded()`:

| Budget | Default | Exhaustion behavior |
| --- | ---: | --- |
| All admitted command evidence | 256 | Evict an observed, unleased terminal result; otherwise return `BLOCKED / BOUNDED_BACKPRESSURE` |
| Retired resource evidence | 256 | Evict oldest retired evidence |
| Resource identities | 65536 | Reject new IDs with `RESOURCE_IDENTITY_BUDGET_EXHAUSTED` |
| Resident resource generations | 16384 | Reject admission with `RESIDENT_GENERATION_BUDGET_EXHAUSTED` |
| Simultaneous command evidence leases | Command capacity | Reject acquisition before creating another lease |

The separate frame concurrency policy also limits pending native submissions and CPU snapshots.
Resident evidence is always protected. History capacity never destroys a resource or releases a
GPU/composition dependency. Native allocations in a failed submission remain owned until close
can establish completion or device loss; a close timeout retains ownership for retry.

## Command Queries

An admitted command reserves evidence capacity before submission. Its recorded state and first
terminal result remain available even when other work completes or the reader is delayed. A query
that returns terminal evidence marks that result observed. Later admission may evict it. Queries
of `RECORDED`, resource evidence, CPU frames, statistics or unrelated sequences do not consume
that protection. Poll each admitted transaction through terminal evidence, including upload-only
commands. If results are never observed, admission eventually reports bounded backpressure.

`RendererCommandAccess.commandExecutionEvidence` retains its `Optional` result. Empty means there
is no retained result and proves neither completion nor failure. For explicit absence semantics:

```java
RendererEvidenceAccess evidence = renderer.extension(RendererEvidenceAccess.class).orElseThrow();
EvidenceQuery<CommandExecutionEvidence> query = evidence.queryCommandExecutionEvidence(sequence);
switch (query.status()) {
    case AVAILABLE -> consume(query.evidence().orElseThrow());
    case UNKNOWN -> handleUnknownSequence();
    case OUTSIDE_RETENTION_WINDOW -> handleExpiredHistory();
    case UNSUPPORTED -> handleUnavailableProvider();
}
```

`OUTSIDE_RETENTION_WINDOW` means the requested identity is beyond the provider's exact historical
knowledge. It does not claim skipped sequence numbers were submitted. Retained pending or leased
results remain `AVAILABLE` even below that historical watermark. Failed execution remains
`AVAILABLE` with its typed reason. Negative sequences are invalid. Queries after close fail.

For multiple or delayed readers, acquire `retainCommandEvidence(sequence)` before the first terminal
observation, and close the returned `EvidenceLease` when all readers finish. Separate readers may
hold separate leases. Closing a lease is idempotent, including after renderer close; releasing one
reader never releases another. A lease pins evidence only, not resource contents. Retrying an
unknown/future lease fails without changing history. Caller-retained snapshots and leases are
caller-owned memory and must themselves be released by the caller.

## Resource History and Identity

Resource retirement returns its immutable terminal evidence in `ResourceTransactionEvidence`.
Retired results are therefore immediately eligible for the bounded retired-history window. Keep
that returned value if later audit requires it. Resident generations and their current mutation
remain queryable independently of history capacity. Publication revision, mutation sequence and
last consumer completion remain unchanged by retention.

All arbitrary non-negative, sparse resource IDs remain legal. The provider retains each admitted
ID's highest version and kind for the entire renderer lifetime. Retired-history eviction cannot
enable old-generation replay or buffer/texture kind changes. Because exact replay protection for
arbitrarily many distinct IDs needs arbitrarily much memory, new IDs have an explicit budget.
Exhausting it still permits newer generations of known IDs and resource retirement. Reuse stable
IDs for storage updates. Rejected transactions do not consume identity or generation capacity.
There is no automatic epoch reset and no reset that silently re-enables retired identities.
Native retirement is completed before residency maps and evidence are published. A native cleanup
failure aborts the operation rather than returning a rejected transaction with partial logical state.

## Composition

A composition source must equal the resident generation's current completed output mutation:
both generation and command sequence are checked. Historical command-query eviction does not
invalidate that source. Overwriting the generation invalidates the previous mutation token even
if the previous command evidence is still retained. Merely uploaded/cleared storage without a
published output does not become an authorized composition source.

Source pins prevent overwrite and retirement until the composition producer has finished reading.
The independently owned composed output follows the existing external-consumer lifetime contract.
GPU completion and CPU readback never imply a visibly presented frame. Invalid source requests
return rejection before submission and do not poison an otherwise healthy renderer.

## Observability and Validation

`evidenceRetentionStatistics()` reports policy, command entries, pending/unobserved/evictable counts,
leases, resident generations, retired history, identities, mutations, composition pins, evictions
and admission-budget rejections. Counters describe this renderer lifetime and do not reset through
evidence queries. No expiry timer, forced renderer restart or frame throttling is involved.

`vulkanCommandEvidenceHistorySelfTest` covers 100000 completions with capacity two, pending and
unobserved protection, multiple leases, budget exhaustion, sparse queries, terminal device failure
and close. `vulkanEvidenceRetentionNativeSelfTest` uses a 256 MiB heap and tiny budgets to verify
2000 repeated outputs and 2000 generations using live-heap histograms, every RGBA8 pixel, stale
mutation rejection, pin protection, rollback, sparse IDs and replay rejection after eviction.

The 4.0.1 consumer diagnostic intentionally asserts growth; its failure against a fixed candidate
does not indicate a regression. Consumer validation must use the separately identified 4.0.2
artifact, preserve original reports and run the unchanged 600-second acceptance thresholds.
Provider-local tests alone do not establish downstream integration acceptance.

These native gates also fail on Vulkan validation errors or dropped validation messages. During
the retention regression, validation exposed the earlier incorrect AS input barrier: vertex,
index, transform, AABB and instance input data require `SHADER_READ` at `ACCELERATION_STRUCTURE_BUILD`,
as specified by [vkCmdBuildAccelerationStructuresKHR](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBuildAccelerationStructuresKHR.html).
Both generic and specialized upload paths use that access type. AS object/scratch dependencies
continue to use acceleration-structure read/write access. GPU completion alone is insufficient
to accept a synchronization regression.
