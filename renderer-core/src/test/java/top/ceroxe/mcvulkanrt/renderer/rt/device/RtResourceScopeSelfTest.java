package top.ceroxe.mcvulkanrt.renderer.rt.device;

public final class RtResourceScopeSelfTest {
    private RtResourceScopeSelfTest() {
    }

    public static void main(String[] args) {
        closesInReverseOrderAndDrainsRetainedResources();
        continuesClosingAfterThrowableFailures();
        rejectsInvalidRetainsAndRetainAfterClose();
        closesResourcesInReverseOrder();
        closesAllResourcesWhenSomeCloseFails();
        rejectsRetainAfterClose();
        System.out.println("RtResourceScopeSelfTest passed");
    }

    private static void closesInReverseOrderAndDrainsRetainedResources() {
        RtResourceScope scope = new RtResourceScope();
        StringBuilder order = new StringBuilder();
        scope.retain("parent", () -> order.append("parent"));
        scope.retain("child", () -> order.append("child>"));

        scope.close();
        scope.close();

        require(scope.closed(), "scope should report closed");
        require(scope.retainedCount() == 0, "closed scope should not retain released resources");
        require("child>parent".contentEquals(order), "resources should close in reverse retain order");
    }

    private static void continuesClosingAfterThrowableFailures() {
        RtResourceScope scope = new RtResourceScope();
        StringBuilder order = new StringBuilder();
        scope.retain("parent", () -> order.append("parent"));
        scope.retain("linkage-failure", () -> {
            order.append("linkage>");
            throw new LinkageError("simulated native unload failure");
        });
        scope.retain("child", () -> order.append("child>"));

        RuntimeException failure = expectFailure(scope::close);

        require("child>linkage>parent".contentEquals(order),
                "scope must keep closing parent resources after throwable close failures");
        require(scope.closed(), "failed close should still mark the scope closed");
        require(scope.retainedCount() == 0, "failed close should still drain retained resources");
        require(failure.getMessage().contains("linkage-failure"), "failure should name the first failed resource");
        require(failure.getCause() instanceof LinkageError, "failure should preserve the original throwable cause");
    }

    private static void rejectsInvalidRetainsAndRetainAfterClose() {
        RtResourceScope scope = new RtResourceScope();
        expectFailure(() -> scope.retain(" ", () -> {
        }));
        scope.close();
        RuntimeException late = expectFailure(() -> scope.retain("late", () -> {
        }));
        require(late instanceof IllegalStateException, "retain after close should fail with IllegalStateException");
    }

    // These three cases were part of the former cross-subsystem core test. Keep
    // their exact contract here because RtResourceScope owns native close order.
    private static void closesResourcesInReverseOrder() {
        RtResourceScope scope = new RtResourceScope();
        StringBuilder order = new StringBuilder();
        scope.retain("parent", () -> order.append("parent"));
        scope.retain("child", () -> order.append("child>"));

        scope.close();

        require(scope.closed(), "scope should report closed");
        require(scope.retainedCount() == 0, "scope should release all retained resources");
        require("child>parent".contentEquals(order), "scope should close resources in reverse retain order");
    }

    private static void closesAllResourcesWhenSomeCloseFails() {
        RtResourceScope scope = new RtResourceScope();
        StringBuilder order = new StringBuilder();
        scope.retain("first", () -> order.append("first"));
        scope.retain("failing", () -> {
            order.append("failing>");
            throw new Exception("boom");
        });
        scope.retain("last", () -> order.append("last>"));

        RuntimeException failure = expectFailure(scope::close);

        require("last>failing>first".contentEquals(order), "scope should keep closing after a failure");
        require(scope.retainedCount() == 0, "failed close should still drain retained resources");
        require(failure.getMessage().contains("failing"), "failure should name the failing resource");
    }

    private static void rejectsRetainAfterClose() {
        RtResourceScope scope = new RtResourceScope();
        scope.close();

        RuntimeException failure = expectFailure(() -> scope.retain("late", () -> {
        }));
        require(failure instanceof IllegalStateException, "retain after close should fail with IllegalStateException");
    }

    private static RuntimeException expectFailure(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            return ex;
        }
        throw new AssertionError("expected failure did not occur");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
