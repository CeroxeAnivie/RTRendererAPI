package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.OUT;

/** Compatible lighting acceptance tasks share one renderer, device and hidden window. */
public final class RtxptSharedLightingNativeSelfTest {
    private static final List<String> TASKS=List.of(
        "rtxptNativeLightingMatrixNativeSelfTest","rtxptLightingAttributeNativeSelfTest",
        "rtxptLightingResourceMatrixNativeSelfTest","rtxptMixedResourceNativeSelfTest",
        "rtxptSourceVisibilityNativeSelfTest");

    public static void main(String[] args)throws Exception {
        if(args.length!=0)throw new IllegalArgumentException("Shared acceptance always runs every covered task");
        Files.createDirectories(OUT);var report=OUT.resolve("shared-window-result.json");
        Files.deleteIfExists(report);var rows=new ArrayList<Object>();
        try(var session=new RtxptLightingTestSession()) {
            for(String task:TASKS) {
                long started=System.nanoTime();
                try {
                    switch(task) {
                        case "rtxptNativeLightingMatrixNativeSelfTest" -> {
                            for(String value:List.of("gi","roles","pose","projection","time"))
                                RtxptNativeLightingMatrixNativeSelfTest.run(value,session);
                        }
                        case "rtxptLightingAttributeNativeSelfTest" -> {
                            for(String value:List.of("alpha","texture","deep"))
                                RtxptLightingAttributeNativeSelfTest.run(value,session);
                        }
                        case "rtxptLightingResourceMatrixNativeSelfTest" ->
                            RtxptLightingResourceMatrixNativeSelfTest.run(session.begin("resources/main"),false);
                        case "rtxptMixedResourceNativeSelfTest" ->
                            RtxptMixedResourceNativeSelfTest.run(session.begin("mixed-resource"));
                        case "rtxptSourceVisibilityNativeSelfTest" -> {
                            RtxptSourceVisibilityNativeSelfTest.cpu();
                            RtxptSourceVisibilityNativeSelfTest.run(session);
                        }
                        default -> throw new AssertionError(task);
                    }
                    rows.add(RtxptJson.object("task",task,"passed",true,"elapsedNanos",System.nanoTime()-started));
                } catch(Exception|Error failure) {
                    rows.add(RtxptJson.object("task",task,"passed",false,"failure",failure.toString()));
                    throw failure;
                }
            }
        } catch(Exception|Error failure) {
            Files.writeString(report,RtxptJson.encode(RtxptJson.object("passed",false,"tasks",rows,"failure",failure.toString())));
            throw failure;
        }
        Files.writeString(report,RtxptJson.encode(RtxptJson.object("passed",true,"rendererSessions",1,
            "coveredTasks",TASKS,"tasks",rows)));
        System.out.println("RTXPT_SHARED_LIGHTING PASS coveredTasks="+TASKS.size()+" rendererSessions=1");
    }
}
