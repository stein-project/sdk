// The Stein SDK build.
//
//   ./gradlew build          stein-sdk.jar, the template project zip and the guide in build/sdk; formatting check
//   ./gradlew templateTest   a mod author's path from scratch: SDK setup and a build of the template project
//   ./gradlew spotlessApply  formats the Java code (google-java-format, AOSP style)
//   ./gradlew publishPlugin  the Gradle plugin and the stein-api into build/maven-repo
//
// stein-sdk.jar carries the Loader's stein-api.jar: the loader repository sits next to this one (../loader, or
// -PloaderDir=...), built first (./gradlew build there).
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    java
    alias(libs.plugins.spotless)
}

group = "dev.xavier.stein"
version = Regex("""String VERSION = "([^"]+)"""")
    .find(file("src/main/java/dev/xavier/stein/sdk/Sdk.java").readText())!!.groupValues[1]

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-nowarn")
    // What javac writes without -g: file names and line numbers, as the release builds always had.
    options.debugOptions.debugLevel = "source,lines"
}

val loaderDir = file(findProperty("loaderDir") ?: "../loader")
val steinApi = loaderDir.resolve("build/libs/stein-api.jar")
val steinApiSources = loaderDir.resolve("build/libs/stein-api-sources.jar")

// stein-sdk.jar: the SDK, ASM (BSD, licence alongside) and the Loader's stein-api with sources. Nothing from Mojang or
// MCP: the SDK setup builds the game with readable names on the mod author's machine.
val sdkJar = tasks.register<Jar>("sdkJar") {
    group = "build"
    description = "Builds build/sdk/stein-sdk.jar, the mod author's tool."
    destinationDirectory = layout.buildDirectory.dir("sdk")
    archiveFileName = "stein-sdk.jar"
    manifest.attributes("Main-Class" to "dev.xavier.stein.sdk.Sdk")
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { cp -> cp.map { zipTree(it) } }) {
        exclude("module-info.class", "META-INF/MANIFEST.MF", "META-INF/versions/**")
    }
    from("tools/asm-license.txt") {
        into("META-INF")
        rename { "ASM-LICENSE.txt" }
    }
    from(files(steinApi, steinApiSources)) {
        into("stein")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        check(steinApi.isFile && steinApiSources.isFile) {
            "stein-api.jar not found in $loaderDir/build/libs: build the loader first (./gradlew build there)"
        }
    }
}

val templateZip = tasks.register<Zip>("templateZip") {
    group = "build"
    description = "Packs the template project into build/sdk/stein-mod-template.zip."
    destinationDirectory = layout.buildDirectory.dir("sdk")
    archiveFileName = "stein-mod-template.zip"
    from("template") {
        exclude("build", ".gradle", "bin")
    }
    // Gradle leaves .gitignore files out of every copy; the template project ships its own.
    val gitignore = file("template/.gitignore")
    inputs.file(gitignore)
    doLast {
        val zip = archiveFile.get().asFile.toPath()
        FileSystems.newFileSystem(zip).use { fs ->
            Files.copy(gitignore.toPath(), fs.getPath(".gitignore"))
        }
    }
}

val guide = tasks.register<Copy>("guide") {
    description = "Copies the guide next to the SDK jar."
    from("README.md")
    into(layout.buildDirectory.dir("sdk"))
}

tasks.assemble {
    dependsOn(sdkJar, templateZip, guide)
}

// A mod author's path, from scratch: the SDK setup in its own folder (build/sdk-home), a build of the template project
// copied out of the repository, and a check of what came out — the example screen extends the game's GuiScreen (axu)
// and the methods it overrides have the game's names, otherwise the game would never call them. The setup downloads
// Minecraft 1.8.9 from Mojang and the MCP mappings the first time.
val templateTest = tasks.register("templateTest") {
    group = "verification"
    description = "Sets up the SDK from scratch and builds the template project with it."
    dependsOn(sdkJar, templateZip)
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val sdk = sdkJar.flatMap { it.archiveFile }
    val zip = templateZip.flatMap { it.archiveFile }
    val work = layout.buildDirectory.dir("sdk-test")
    val home = layout.buildDirectory.dir("sdk-home")
    doLast {
        val javaBin = java.get().executablePath.asFile.absolutePath
        val project = work.get().dir("mymod").asFile
        work.get().asFile.deleteRecursively()
        project.mkdirs()
        ant.withGroovyBuilder { "unzip"("src" to zip.get().asFile, "dest" to project) }
        fun run(dir: File, vararg args: String): String {
            val p = ProcessBuilder(javaBin, *args).directory(dir).redirectErrorStream(true)
            p.environment()["STEIN_SDK_HOME"] = home.get().asFile.absolutePath
            val proc = p.start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "java ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        run(project, "-jar", sdk.get().asFile.absolutePath, "setup")
        run(project, "-jar", sdk.get().asFile.absolutePath, "build")
        val mod = project.resolve("build/ExampleMod.steinmod")
        val javap = java.get().metadata.installationPath.file("bin/javap").asFile.absolutePath
        val sig = ProcessBuilder(javap, "-p", "-cp", mod.absolutePath, "com.example.examplemod.ExampleScreen")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        for (want in listOf("extends axu", "public void b();", "protected void a(avs);",
                "public void a(int, int, float);")) {
            check(sig.contains(want)) { "ExampleScreen is missing '$want':\n$sig" }
        }
        logger.lifecycle("template built and translated to the game's names: $mod")
    }
}

val publishPlugin = tasks.register("publishPlugin") {
    group = "publishing"
    description = "Publishes the Gradle plugin and the stein-api into build/maven-repo (-PsteinRepo=... elsewhere)."
    dependsOn(gradle.includedBuild("stein-gradle").task(":publishAllPublicationsToSteinRepository"))
    val repo = file(findProperty("steinRepo") ?: "build/maven-repo")
    // Nothing from Mojang or MCP in what goes to maven: neither the plugin nor the API.
    doLast {
        val jars = repo.resolve("dev/xavier/stein").walkTopDown().filter { it.name.endsWith(".jar") }.toList()
        for (jar in jars) {
            ZipFile(jar).use { z ->
                val bad = z.entries().toList().map { it.name }.filter { Regex("1\\.8\\.9|minecraft").containsMatchIn(it) }
                check(bad.isEmpty()) { "$jar has game files inside: $bad" }
            }
        }
        logger.lifecycle("Gradle plugin: ${jars.size} jar(s) in $repo, none with game files")
    }
}

spotless {
    // LF everywhere, as committed (.editorconfig); Windows checkouts too.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target("src/**/*.java", "template/src/**/*.java", "examples/*/src/**/*.java", "gradle-plugin/java/**/*.java")
        googleJavaFormat(libs.versions.google.java.format.get()).aosp().reorderImports(true)
    }
}
