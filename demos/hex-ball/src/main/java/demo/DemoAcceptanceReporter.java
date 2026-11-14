package demo;

import java.io.PrintStream;
import java.util.Objects;

import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.RendererHealth;

/** Writes stable, evidence-complete smoke records independently of the human-oriented HUD. */
final class DemoAcceptanceReporter {
    private DemoAcceptanceReporter() {
    }

    static void printDiagnostics(RendererDiagnostics diagnostics, PrintStream output) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        PrintStream checkedOutput = Objects.requireNonNull(output, "output");
        checkedOutput.println("Acceptance renderer diagnostics: status=" + diagnostics.status()
                + "; submittedSequence=" + diagnostics.latestSubmittedFrameSequence()
                + "; completedSequence=" + diagnostics.latestCompletedFrameSequence()
                + "; recovery=" + diagnostics.deviceRecovery()
                + "; gpuTiming=" + diagnostics.frameGpuTiming());
        checkedOutput.println(
                "Acceptance frame-generation evidence: " + diagnostics.frameGenerationEvidence()
        );
        diagnostics.technologyExecutionEvidence().technologies().forEach((technology, evidence) ->
                checkedOutput.println(
                        "Acceptance technology evidence: technology=" + technology
                                + "; evidence=" + evidence
                )
        );
    }

    static void printHealth(RendererHealth health, PrintStream output) {
        Objects.requireNonNull(output, "output").println(
                "Acceptance renderer final health: " + Objects.requireNonNull(health, "health")
        );
    }
}
