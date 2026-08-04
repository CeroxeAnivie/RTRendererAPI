package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.feature.VulkanFeatureOpenContext;

import java.util.Objects;

/** Independent native ownership for NRD and RTXMU device sessions. */
final class NvidiaNativeFeatureSessions implements AutoCloseable {
    /**
     * Narrow native operation table retained by the owner that opened the handles.
     *
     * <p>Keeping creation and destruction on the same table is more than a test seam: it makes
     * the ownership contract explicit and prevents a future alternate bridge from opening a
     * handle that is later destroyed through an unrelated global implementation.</p>
     */
    interface Operations {
        long openNrd();

        long openRtxmu();

        void closeNrd(long handle);

        void closeRtxmu(long handle);
    }

    private long nrdHandle;
    private long rtxmuHandle;
    private final Throwable nrdOpenFailure;
    private final Throwable rtxmuOpenFailure;
    private final Operations operations;
    private boolean closed;

    private NvidiaNativeFeatureSessions(
            long nrdHandle,
            long rtxmuHandle,
            Throwable nrdOpenFailure,
            Throwable rtxmuOpenFailure,
            Operations operations
    ) {
        this.nrdHandle = nrdHandle;
        this.rtxmuHandle = rtxmuHandle;
        this.nrdOpenFailure = nrdOpenFailure;
        this.rtxmuOpenFailure = rtxmuOpenFailure;
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    static NvidiaNativeFeatureSessions open(
            VulkanFeatureOpenContext context,
            NvidiaNativeBridge.Probe probe,
            RendererFeaturePreference nrdPreference,
            RendererFeaturePreference rtxmuPreference
    ) {
        VulkanFeatureOpenContext checked = Objects.requireNonNull(context, "context");
        return open(
                probe,
                nrdPreference,
                rtxmuPreference,
                new Operations() {
                    @Override
                    public long openNrd() {
                        return NvidiaNativeBridge.openNrd(checked);
                    }

                    @Override
                    public long openRtxmu() {
                        return NvidiaNativeBridge.openRtxmu(checked);
                    }

                    @Override
                    public void closeNrd(long handle) {
                        NvidiaNativeBridge.closeNrd(handle);
                    }

                    @Override
                    public void closeRtxmu(long handle) {
                        NvidiaNativeBridge.closeRtxmu(handle);
                    }
                }
        );
    }

    /** Opens independent feature owners through a deterministic package-local operation table. */
    static NvidiaNativeFeatureSessions open(
            NvidiaNativeBridge.Probe probe,
            RendererFeaturePreference nrdPreference,
            RendererFeaturePreference rtxmuPreference,
            Operations operations
    ) {
        NvidiaNativeBridge.Probe checkedProbe = Objects.requireNonNull(probe, "probe");
        RendererFeaturePreference checkedNrdPreference =
                Objects.requireNonNull(nrdPreference, "nrdPreference");
        RendererFeaturePreference checkedRtxmuPreference =
                Objects.requireNonNull(rtxmuPreference, "rtxmuPreference");
        Operations checkedOperations = Objects.requireNonNull(operations, "operations");
        OpenResult nrd = OpenResult.disabled();
        OpenResult rtxmu = OpenResult.disabled();
        try {
            if (checkedNrdPreference.requested()
                    && checkedProbe.supports(NvidiaNativeBridge.NRD)) {
                nrd = openOne(
                        NvidiaNativeBridge.NRD,
                        checkedNrdPreference,
                        "NRD",
                        checkedOperations
                );
            }
            if (checkedRtxmuPreference.requested()
                    && checkedProbe.supports(NvidiaNativeBridge.RTX_MEMORY_UTILITY)) {
                rtxmu = openOne(
                        NvidiaNativeBridge.RTX_MEMORY_UTILITY,
                        checkedRtxmuPreference,
                        "RTXMU",
                        checkedOperations
                );
            }
            return new NvidiaNativeFeatureSessions(
                    nrd.handle,
                    rtxmu.handle,
                    nrd.failure,
                    rtxmu.failure,
                    checkedOperations
            );
        } catch (RuntimeException | Error failure) {
            closeRtxmuSuppressing(rtxmu.handle, checkedOperations, failure);
            closeNrdSuppressing(nrd.handle, checkedOperations, failure);
            throw failure;
        }
    }

    private static OpenResult openOne(
            int capability,
            RendererFeaturePreference preference,
            String feature,
            Operations operations
    ) {
        try {
            long handle = capability == NvidiaNativeBridge.NRD
                    ? operations.openNrd()
                    : operations.openRtxmu();
            if (handle == 0L) {
                throw new IllegalStateException(feature + " native session returned a null handle");
            }
            return new OpenResult(handle, null);
        } catch (RuntimeException | LinkageError failure) {
            if (preference == RendererFeaturePreference.REQUIRED
                    || failure instanceof RendererDeviceException) {
                throw failure;
            }
            return new OpenResult(0L, failure);
        }
    }

    long nrdHandle() {
        requireOpen();
        return nrdHandle;
    }

    long rtxmuHandle() {
        requireOpen();
        return rtxmuHandle;
    }

    boolean nrdAvailable() {
        return !closed && nrdHandle != 0L;
    }

    boolean rtxmuAvailable() {
        return !closed && rtxmuHandle != 0L;
    }

    Throwable nrdOpenFailure() {
        return nrdOpenFailure;
    }

    Throwable rtxmuOpenFailure() {
        return rtxmuOpenFailure;
    }

    boolean empty() {
        return nrdHandle == 0L && rtxmuHandle == 0L;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        long closingRtxmu = rtxmuHandle;
        long closingNrd = nrdHandle;
        rtxmuHandle = 0L;
        nrdHandle = 0L;
        Throwable failure = null;
        try {
            if (closingRtxmu != 0L) operations.closeRtxmu(closingRtxmu);
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        }
        try {
            if (closingNrd != 0L) operations.closeNrd(closingNrd);
        } catch (RuntimeException | Error closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("NVIDIA native feature sessions are closed");
    }

    private static void closeNrdSuppressing(
            long handle,
            Operations operations,
            Throwable failure
    ) {
        if (handle == 0L) return;
        try {
            operations.closeNrd(handle);
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeRtxmuSuppressing(
            long handle,
            Operations operations,
            Throwable failure
    ) {
        if (handle == 0L) return;
        try {
            operations.closeRtxmu(handle);
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private record OpenResult(long handle, Throwable failure) {
        private OpenResult {
            if (handle != 0L && failure != null) {
                throw new IllegalArgumentException("native open result cannot contain both handle and failure");
            }
        }

        private static OpenResult disabled() {
            return new OpenResult(0L, null);
        }
    }
}
