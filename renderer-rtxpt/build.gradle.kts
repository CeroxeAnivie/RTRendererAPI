import java.security.MessageDigest
import java.util.HexFormat
import java.nio.file.Files

plugins { `java-library` }
description = "Thin in-process JNI host for NVIDIA RTXPT managed rendering."
dependencies { compileOnly(project(":renderer-api")); testImplementation(project(":renderer-api")) }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) }; withSourcesJar(); withJavadocJar() }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<Test>().configureEach { failOnNoDiscoveredTests = false }
tasks.withType<JavaExec>().configureEach {
    workingDir(rootProject.projectDir)
    jvmArgs("-Xmx512m", "-XX:-UseJVMCICompiler", "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")
    systemProperties(System.getProperties().stringPropertyNames().filter { it.startsWith("top.ceroxe.rt.rtxpt.") }.associateWith(System::getProperty))
}
tasks.register<Exec>("buildRtxptBridge") {
    group = "build"
    workingDir(rootProject.projectDir)
    commandLine("pwsh", "-NoProfile", "-File", "tools/Build-RtxptBridge.ps1")
}
tasks.register<JavaExec>("rtxptAdapterSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest")
}
tasks.register<JavaExec>("rtxptNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeSelfTest")
}

tasks.register<JavaExec>("rtxptPackagedNativeSelfTest") {
    group = "verification"
    description = "Runs the native contract from the bundled JAR outside the source checkout."
    dependsOn(tasks.testClasses, tasks.jar)
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get() + files(tasks.jar)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeSelfTest")
    systemProperty("top.ceroxe.rt.rtxpt.testOutput", layout.buildDirectory.dir("reports/packaged-native").get().asFile.absolutePath)
    doFirst {
        check(!systemProperties.containsKey("top.ceroxe.rt.rtxpt.runtime") && !systemProperties.containsKey("top.ceroxe.rt.rtxpt.bridge")) {
            "Packaged acceptance must use the JAR payload, without native overrides"
        }
        workingDir(Files.createTempDirectory("rtxpt-packaged-consumer-").toFile())
    }
    doLast { workingDir.deleteRecursively() }
}
tasks.named("check") { dependsOn("rtxptAdapterSelfTest") }

val nativeRuntime = rootProject.layout.buildDirectory.dir("rtxpt-sdk/bin")
val runtimeArchive = tasks.register<Zip>("packageRtxptRuntime") {
    group = "distribution"
    description = "Packages the pinned RTXPT native renderer, runtime shaders and upstream licenses."
    archiveFileName.set("rtxpt-runtime.zip")
    destinationDirectory.set(layout.buildDirectory.dir("native-bundle"))
    from(nativeRuntime) {
        include("*.dll", "sl.*.json", "D3D12/D3D12Core.dll", "Assets/**", "ThirdPartyLicenses/**",
            "ShaderPrecompiled/**", "ShaderDynamic/Source/**", "ShaderDynamic/Tools/d3d12/x64/*.dll",
            "ShaderDynamic/Tools/d3d12/x64/dxc.exe")
        exclude("**/*.pdb", "**/.gitkeep")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doFirst {
        listOf("rtrenderer_rtxpt.dll", "ThirdPartyLicenses/RTXPT.txt").forEach {
            check(nativeRuntime.get().file(it).asFile.isFile) { "Build the native runtime first: tools/Build-RtxptBridge.ps1 (missing $it)" }
        }
    }
}
val runtimeDigest = tasks.register("hashRtxptRuntime") {
    dependsOn(runtimeArchive)
    val archive = runtimeArchive.flatMap { it.archiveFile }
    val output = layout.buildDirectory.file("native-bundle/rtxpt-runtime.sha256")
    inputs.file(archive); outputs.file(output)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256")
        archive.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n=input.read(buffer); if (n<0) break; hash.update(buffer,0,n) }
        }
        output.get().asFile.writeText(HexFormat.of().formatHex(hash.digest()) + "\n", Charsets.US_ASCII)
    }
}
tasks.named<Jar>("jar") {
    dependsOn(runtimeDigest)
    from("NOTICE-RTXPT.txt") { into("META-INF") }
    from(runtimeArchive.flatMap { it.archiveFile }) { into("META-INF/native/windows-x86_64") }
    from(layout.buildDirectory.file("native-bundle/rtxpt-runtime.sha256")) { into("META-INF/native/windows-x86_64") }
}
