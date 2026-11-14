plugins { java }
val rendererVersion = providers.gradleProperty("rendererVersion").get()
dependencies { implementation("top.ceroxe.rt:renderer-rtxpt:"+rendererVersion) }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile>().configureEach { options.encoding="UTF-8"; options.release=25; options.compilerArgs.addAll(listOf("-Xlint:all","-Werror")) }
val verifyPublishedRuntimeClasspath = tasks.register("verifyPublishedRuntimeClasspath") {
    doLast {
        val modules=configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }.map { it.group+":"+it.name+":"+it.version }.toSet()
        check(setOf("top.ceroxe.rt:renderer-api:"+rendererVersion,"top.ceroxe.rt:renderer-rtxpt:"+rendererVersion).all(modules::contains))
        check(modules.none { it.startsWith("top.ceroxe.rt:renderer-core:")||it.startsWith("top.ceroxe.rt:renderer-nvidia:") })
    }
}
val verifyPublishedApiRuntime = tasks.register<JavaExec>("verifyPublishedApiRuntime") {
    dependsOn(tasks.classes,verifyPublishedRuntimeClasspath)
    classpath=sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PublishedApiRuntimeSelfTest")
    jvmArgs("-Xmx512m","-Dfile.encoding=UTF-8")
    providers.gradleProperty("runtimeCache").orNull?.let { systemProperty("top.ceroxe.rt.rtxpt.cache",it) }
}
tasks.named("check") { dependsOn(verifyPublishedApiRuntime) }
