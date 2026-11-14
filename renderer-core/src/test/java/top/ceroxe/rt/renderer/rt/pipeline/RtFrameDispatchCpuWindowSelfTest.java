package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.Arrays;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameDispatchTiming.Stage;

public final class RtFrameDispatchCpuWindowSelfTest {
   private RtFrameDispatchCpuWindowSelfTest() {
   }

   public static void main(String[] args) {
      RtFrameDispatchCpuWindow window = new RtFrameDispatchCpuWindow();
      long[] first = stageVector(1000L);
      long[] second = stageVector(2000L);
      require(window.record(first, 100L) == null, "new aggregation window closed immediately");
      require(window.record(second, 1000000099L) == null, "aggregation window closed before one second");
      String completed = window.record(second, 1000000100L);
      require(completed != null && completed.startsWith("samples=3, micros={"), "one-second boundary did not publish the complete sample count");

      for(RtFrameDispatchTiming.Stage stage : Stage.values()) {
         require(completed.contains(stage.logName() + "=5"), "stage total was not converted to aggregate microseconds: " + stage.logName());
      }

      require(window.record(first, 1000000101L) == null, "completed window did not reset its sample state");
      expectIllegalArgument(() -> window.record(new long[0], 2000000000L));
      expectNullPointer(() -> window.record((long[])null, 2000000000L));
      System.out.println("RtFrameDispatchCpuWindowSelfTest passed");
   }

   private static long[] stageVector(long nanos) {
      long[] values = new long[Stage.values().length];
      Arrays.fill(values, nanos);
      return values;
   }

   private static void expectIllegalArgument(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected IllegalArgumentException");
      } catch (IllegalArgumentException value2) {
      }
   }

   private static void expectNullPointer(Runnable action) {
      try {
         action.run();
         throw new AssertionError("expected NullPointerException");
      } catch (NullPointerException value2) {
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
