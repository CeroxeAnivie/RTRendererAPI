package demo;

/** Independent medium-demo entry; reuses the existing native-access launcher. */
public final class MediumDemoLauncher {
    private MediumDemoLauncher() { }
    public static void main(String[] arguments) throws Exception {
        DemoLauncher.launch(arguments,RtxptMediumDemo::main);
    }
}
