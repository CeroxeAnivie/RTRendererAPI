package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable generic evidence inventory; counts exclude snapshots retained by callers. */
public record EvidenceRetentionStatistics(
        EvidenceRetentionPolicy policy,
        int commandEntries,
        int pendingCommands,
        int unobservedCompletedCommands,
        int commandLeases,
        int evictableCommands,
        int residentGenerations,
        int retiredResourceHistory,
        int resourceIdentities,
        int inFlightMutations,
        int compositionPins,
        long evictedCommands,
        long evictedResources,
        long commandBudgetRejections,
        long resourceBudgetRejections
) {
    public EvidenceRetentionStatistics {
        Objects.requireNonNull(policy, "policy");
        if (commandEntries < 0 || pendingCommands < 0 || unobservedCompletedCommands < 0
                || commandLeases < 0 || evictableCommands < 0 || residentGenerations < 0
                || retiredResourceHistory < 0 || resourceIdentities < 0 || inFlightMutations < 0
                || compositionPins < 0 || evictedCommands < 0 || evictedResources < 0
                || commandBudgetRejections < 0 || resourceBudgetRejections < 0
                || commandEntries > policy.commandCapacity()
                || pendingCommands > commandEntries
                || unobservedCompletedCommands > commandEntries
                || evictableCommands > commandEntries
                || (long) pendingCommands + unobservedCompletedCommands + evictableCommands > commandEntries
                || commandLeases > policy.commandCapacity()
                || residentGenerations > policy.residentGenerationCapacity()
                || retiredResourceHistory > policy.retiredResourceCapacity()
                || resourceIdentities > policy.resourceIdentityCapacity()
                || inFlightMutations > residentGenerations) {
            throw new IllegalArgumentException("invalid evidence retention inventory");
        }
    }
}
