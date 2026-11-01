plugins { `java-library` }

description = "Thin JNI scene adapter for the unmodified LuxCoreRender engine."
dependencies {
    compileOnly(project(":renderer-api"))
    testImplementation(project(":renderer-api"))
}
java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<Test>().configureEach { failOnNoDiscoveredTests = false }

val sdk = providers.gradleProperty("luxcoreSdk").orElse(providers.environmentVariable("LUXCORE_SDK"))
val upstream = providers.gradleProperty("luxcoreSource").orElse(providers.environmentVariable("LUXCORE_SOURCE"))
val boost = providers.gradleProperty("boostInclude").orElse(providers.environmentVariable("BOOST_INCLUDE"))
val cmake = providers.gradleProperty("cmakeExecutable").orElse("cmake")
tasks.register<Exec>("configureLuxCoreBridge") {
    group = "build"
    doFirst {
        require(sdk.isPresent && upstream.isPresent && boost.isPresent) { "Install the pinned SDK with tools/Install-LuxCoreSdk.ps1; set LUXCORE_SDK, LUXCORE_SOURCE and BOOST_INCLUDE." }
        commandLine(cmake.get(), "-S", "src/main/cpp", "-B", layout.buildDirectory.dir("cmake").get().asFile,
            "-A", "x64", "-DLUXCORE_SDK=${sdk.get()}", "-DLUXCORE_SOURCE=${upstream.get()}", "-DBOOST_INCLUDE=${boost.get()}",
            "-DJAVA_HOME=${System.getProperty("java.home")}")
    }
}
tasks.register<JavaExec>("luxcoreNativeSelfTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.luxcore.LuxCoreNativeSelfTest")
}
tasks.withType<JavaExec>().configureEach {
    workingDir(rootProject.projectDir)
    jvmArgs("-Xmx256m", "-XX:-UseJVMCICompiler", "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")
    systemProperties(System.getProperties().stringPropertyNames().filter { it.startsWith("top.ceroxe.rt.luxcore.") }.associateWith(System::getProperty))
}
tasks.named("check") { dependsOn("luxcoreAdapterSelfTest") }
tasks.register<Exec>("buildLuxCoreBridge") {
    group = "build"
    dependsOn("configureLuxCoreBridge")
    commandLine(cmake.get(), "--build", layout.buildDirectory.dir("cmake").get().asFile,
        "--config", "Release", "--parallel", "1")
}
tasks.register<JavaExec>("luxcoreAdapterSelfTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.luxcore.LuxCoreAdapterSelfTest")
}
