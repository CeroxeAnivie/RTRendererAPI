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
tasks.named("check") { dependsOn("rtxptAdapterSelfTest") }
