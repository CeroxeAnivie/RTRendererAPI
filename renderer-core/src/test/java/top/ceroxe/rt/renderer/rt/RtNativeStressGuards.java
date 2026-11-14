package top.ceroxe.rt.renderer.rt;

import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;

final class RtNativeStressGuards {
   private static final double PATHOLOGICAL_FRAME_FRACTION = 0.98;

   private RtNativeStressGuards() {
   }

   static void assertFrameNotPathological(RtFrameSnapshot snapshot, String label) {
      require(snapshot != null, label + " did not produce an RT frame snapshot");
      int totalPixels = snapshot.width() * snapshot.height();
      require(snapshot.foregroundPixels() > 0, label + " collapsed into a pure miss/sky frame: " + snapshot.asLogFragment());
      require(snapshot.backgroundPixels() < pathologicalPixelCount(totalPixels), label + " is almost entirely miss/sky output: " + snapshot.asLogFragment());
      byte[] pixels = snapshot.copyRgba8();
      int whitePixels = 0;
      int blackPixels = 0;

      for(int y = 0; y < snapshot.height(); ++y) {
         for(int x = 0; x < snapshot.width(); ++x) {
            int pixel = RtFrameSnapshot.pixel(pixels, snapshot.width(), x, y);
            int red = pixel & 255;
            int green = pixel >>> 8 & 255;
            int blue = pixel >>> 16 & 255;
            if (red >= 245 && green >= 245 && blue >= 245) {
               ++whitePixels;
            }

            if (red <= 8 && green <= 8 && blue <= 8) {
               ++blackPixels;
            }
         }
      }

      int pathologicalPixels = pathologicalPixelCount(totalPixels);
      require(whitePixels < pathologicalPixels, label + " collapsed into an almost pure white frame: whitePixels=" + whitePixels + "/" + totalPixels + ", snapshot=" + snapshot.asLogFragment());
      require(blackPixels < pathologicalPixels, label + " collapsed into an almost pure black frame: blackPixels=" + blackPixels + "/" + totalPixels + ", snapshot=" + snapshot.asLogFragment());
   }

   static long exportCompletedSharedFrame(RtCore rtCore, long completedFrameStateSequence, long lastExportedSharedFrameSequence, String label) {
      require(completedFrameStateSequence >= 0L, label + " cannot export a shared frame before any RT frame has completed");
      if (completedFrameStateSequence <= lastExportedSharedFrameSequence) {
         return lastExportedSharedFrameSequence;
      } else {
         long advertisedSharedSequence = rtCore.latestSharedFrameSequence();
         require(advertisedSharedSequence >= completedFrameStateSequence, label + " completed RT frame is not available as a shared GPU frame, completedSeq=" + completedFrameStateSequence + ", advertisedSharedSeq=" + advertisedSharedSequence + ", lastExportedSharedSeq=" + lastExportedSharedFrameSequence + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
         RtCore.SharedFrameImage sharedFrame = rtCore.exportLatestSharedFrameImage();
         require(sharedFrame != null, label + " advertised a shared GPU frame but export returned null, advertisedSharedSeq=" + advertisedSharedSequence + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());

         long value9;
         try {
            require(sharedFrame.frameStateSequence() >= completedFrameStateSequence, label + " exported a stale shared GPU frame, completedSeq=" + completedFrameStateSequence + ", exported=" + sharedFrame.asLogFragment() + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            require(sharedFrame.width() > 1 && sharedFrame.height() > 1, label + " exported a degenerate shared GPU frame: " + sharedFrame.asLogFragment());
            value9 = sharedFrame.frameStateSequence();
         } finally {
            sharedFrame.close();
         }

         return value9;
      }
   }

   static long sampleCompletedSharedFrame(RtCore rtCore, boolean enabled, long completedFrameStateSequence, long lastExportedSharedFrameSequence, int minCompletedSequenceDelta, boolean force, String label) {
      if (enabled && completedFrameStateSequence >= 0L) {
         if (!force && lastExportedSharedFrameSequence >= 0L && completedFrameStateSequence - lastExportedSharedFrameSequence < (long)Math.max(1, minCompletedSequenceDelta)) {
            long advertisedSharedSequence = rtCore.latestSharedFrameSequence();
            require(advertisedSharedSequence >= lastExportedSharedFrameSequence, label + " shared GPU frame sequence regressed between sampled exports, advertisedSharedSeq=" + advertisedSharedSequence + ", lastExportedSharedSeq=" + lastExportedSharedFrameSequence + ", completedSeq=" + completedFrameStateSequence + ", activity=" + rtCore.runtimeActivity().asLogFragment() + ", readiness=" + rtCore.sceneReadiness().asLogFragment() + ", summary=" + rtCore.summary().asLogFragment());
            return lastExportedSharedFrameSequence;
         } else {
            return exportCompletedSharedFrame(rtCore, completedFrameStateSequence, lastExportedSharedFrameSequence, label);
         }
      } else {
         return lastExportedSharedFrameSequence;
      }
   }

   static void assertSharedFrameReachedCompletedFrame(boolean enabled, long latestCompletedFrameStateSequence, long lastExportedSharedFrameSequence, String label) {
      if (enabled && latestCompletedFrameStateSequence >= 0L) {
         require(lastExportedSharedFrameSequence >= latestCompletedFrameStateSequence, label + " ended with a completed RT frame that never reached the shared GPU output path, latestCompletedSeq=" + latestCompletedFrameStateSequence + ", lastExportedSharedSeq=" + lastExportedSharedFrameSequence);
      }
   }

   static void assertCommandAndFencePoolReused(RtCore rtCore, String label) {
      String summary = rtCore.summary().asLogFragment();
      long commandBufferAllocations = sumSummaryLong(summary, "commandBufferAllocations");
      long commandBufferReuses = sumSummaryLong(summary, "commandBufferReuses");
      long fenceAllocations = sumSummaryLong(summary, "fenceAllocations");
      long fenceReuses = sumSummaryLong(summary, "fenceReuses");
      require(commandBufferAllocations > 0L, label + " did not allocate any RT command buffers; summary=" + summary);
      require(fenceAllocations > 0L, label + " did not allocate any RT fences; summary=" + summary);
      require(commandBufferReuses > 0L, label + " never reused RT command buffers under sustained load; summary=" + summary);
      require(fenceReuses > 0L, label + " never reused RT fences under sustained load; summary=" + summary);
   }

   private static int pathologicalPixelCount(int totalPixels) {
      return (int)Math.floor((double)totalPixels * 0.98);
   }

   private static long sumSummaryLong(String summary, String key) {
      long sum = 0L;
      boolean present = false;
      String prefix = key + "=";

      int valueEnd;
      for(int searchFrom = 0; searchFrom < summary.length(); searchFrom = valueEnd) {
         int start = summary.indexOf(prefix, searchFrom);
         if (start < 0) {
            break;
         }

         present = true;
         int valueStart = start + prefix.length();

         for(valueEnd = valueStart; valueEnd < summary.length() && Character.isDigit(summary.charAt(valueEnd)); ++valueEnd) {
         }

         require(valueEnd > valueStart, "summary key has no numeric value: " + key + "; summary=" + summary);
         sum += Long.parseLong(summary.substring(valueStart, valueEnd));
      }

      require(present, "summary key was not present: " + key + "; summary=" + summary);
      return sum;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
