package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;

public final class OpticalTriangleEmitterSelfTest {
    public static void main(String[] arguments) {
        var volumes = new ArrayList<>(List.of(8L, 4L));
        var emitter = triangle(volumes);
        volumes.clear();
        require(emitter.mediumInstanceIds().equals(List.of(4L, 8L)), "Containment must be a canonical immutable value");
        reject(() -> triangle(List.of(4L, 4L)));
        reject(() -> triangle(List.of(-1L)));
        reject(() -> triangle(List.of(0L, 1L, 2L, 3L, 4L)));
        reject(() -> new OpticalTriangleEmitter(0, 0, 0, 1, 0, 0, -2, 0, 0, Rgb.WHITE, 5, List.of()));
        reject(() -> new OpticalTriangleEmitter(0, 0, 0, 1, 0, 0, 0, 1, 0, Rgb.WHITE, 0, List.of()));
        reject(() -> new OpticalTriangleEmitter(0, 0, Double.MAX_VALUE, 1, 0, 0, 0, 1, 0, Rgb.WHITE, 5, List.of()));

        var camera = CameraState.lookAt(0, 1, 4, 0, 1, 0).build();
        var sources = new ArrayList<>(List.of(emitter));
        var frame = RenderFrameRequest.builder(1, 64, 64, camera).opticalTriangleEmitters(sources).build();
        sources.clear();
        var copy = frame.toBuilder().build();
        require(frame.opticalTriangleEmitters().equals(List.of(emitter)), "Frame retained a mutable emitter list");
        require(copy.equals(frame) && copy.hashCode() == frame.hashCode(), "Frame copy lost its emitters");
        var cleared = frame.toBuilder().opticalTriangleEmitters(List.of()).build();
        require(cleared.opticalTriangleEmitters().isEmpty() && !cleared.equals(frame), "Empty list must remove every emitter");
        System.out.println("OpticalTriangleEmitterSelfTest passed");
    }

    private static OpticalTriangleEmitter triangle(List<Long> ids) {
        return new OpticalTriangleEmitter(-3.5, 1, 2, 1, 0, 0, 0, 1, 0, Rgb.WHITE, 5, ids);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid emitter to be rejected");
    }
}
