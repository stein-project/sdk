// The Stein SDK build.
//
//   ./gradlew build          stein-sdk.jar, the template project zip and the guide in build/sdk; formatting check
//   ./gradlew templateTest   a mod author's path from scratch: SDK setup, then the template, the examples and the
//                            API test mod built with the command line
//   ./gradlew pluginTest     the template built with its own Gradle wrapper and the Gradle plugin from build/maven-repo
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
    manifest.attributes(
        "Main-Class" to "dev.xavier.stein.sdk.Sdk",
        "Implementation-Title" to "Stein SDK",
        "Implementation-Version" to project.version,
    )
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { cp -> cp.map { zipTree(it) } }) {
        exclude("module-info.class", "META-INF/MANIFEST.MF", "META-INF/versions/**")
    }
    from(files("LICENSE", "NOTICE")) {
        into("META-INF")
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
        // gradlew runs as is on Linux and macOS after unzipping.
        filesMatching("gradlew") {
            permissions { unix("rwxr-xr-x") }
        }
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
// and the methods it overrides have the game's names, otherwise the game would never call them. Then the examples
// and the API test mod (test/apitest), so a change in the API that breaks them shows up here. The setup downloads
// Minecraft 1.8.9 from Mojang and the MCP mappings the first time.
val templateTest = tasks.register("templateTest") {
    group = "verification"
    description = "Sets up the SDK from scratch and builds the template, the examples and the API test mod with it."
    dependsOn(sdkJar, templateZip)
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val sdk = sdkJar.flatMap { it.archiveFile }
    val zip = templateZip.flatMap { it.archiveFile }
    val work = layout.buildDirectory.dir("sdk-test")
    val home = layout.buildDirectory.dir("sdk-home")
    val mods = (file("examples").listFiles { f -> f.isDirectory }.orEmpty().sorted() + file("test/apitest"))
        .filter { it.resolve("src/main/resources/steinmod.json").isFile }
    inputs.files(mods.map { fileTree(it) { exclude("build/**", ".gradle/**", "bin/**") } })
    doLast {
        val javaBin = java.get().executablePath.asFile.absolutePath
        val sdkPath = sdk.get().asFile.absolutePath
        val project = work.get().dir("mymod").asFile
        work.get().asFile.deleteRecursively()
        project.mkdirs()
        ant.withGroovyBuilder { "unzip"("src" to zip.get().asFile, "dest" to project) }
        fun run(dir: File, vararg args: String): String {
            val p = ProcessBuilder(javaBin, *args).directory(dir).redirectErrorStream(true)
            p.environment()["STEIN_SDK_HOME"] = home.get().asFile.absolutePath
            val proc = p.start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "java ${args.joinToString(" ")} in $dir failed:\n$out" }
            return out
        }
        run(project, "-jar", sdkPath, "setup")
        run(project, "-jar", sdkPath, "build")
        val mod = project.resolve("build/ExampleMod.steinmod")
        val javap = java.get().metadata.installationPath.file("bin/javap").asFile.absolutePath
        val sig = ProcessBuilder(javap, "-p", "-cp", mod.absolutePath, "com.example.examplemod.ExampleScreen")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        for (want in listOf("extends axu", "public void b();", "protected void a(avs);",
                "public void a(int, int, float);")) {
            check(sig.contains(want)) { "ExampleScreen is missing '$want':\n$sig" }
        }
        logger.lifecycle("template built and translated to the game's names: $mod")
        for (dir in mods) {
            val target = work.get().dir(dir.name).asFile
            copyProject(dir, target)
            val out = run(target, "-jar", sdkPath, "build")
            logger.lifecycle(out.lines().last { it.startsWith("built ") })
        }
    }
}

/** Copies a mod project, without what a build left in it. */
fun copyProject(from: File, to: File) {
    from.walkTopDown()
        .onEnter { it == from || it.name !in setOf("build", ".gradle", "bin") }
        .filter { it.isFile }
        .forEach { it.copyTo(to.resolve(it.relativeTo(from)), overwrite = true) }
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

// What a mod author does with Gradle: the template project, unzipped, built by its own Gradle wrapper with the plugin
// it names in build.gradle — read from build/maven-repo instead of the public Maven repository, and with the new
// stein-sdk.jar (-Pstein.sdkJar) instead of a download. The plugin version the template asks for must be this SDK's.
val pluginTest = tasks.register("pluginTest") {
    group = "verification"
    description = "Builds the template project with its Gradle wrapper and the Gradle plugin from build/maven-repo."
    dependsOn(publishPlugin, sdkJar, templateZip)
    mustRunAfter(templateTest)
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val sdk = sdkJar.flatMap { it.archiveFile }
    val zip = templateZip.flatMap { it.archiveFile }
    val repo = file(findProperty("steinRepo") ?: "build/maven-repo")
    val work = layout.buildDirectory.dir("plugin-test")
    val home = layout.buildDirectory.dir("sdk-home")
    val windows = System.getProperty("os.name").lowercase().contains("win")
    doLast {
        val project = work.get().dir("mymod").asFile
        work.get().asFile.deleteRecursively()
        project.mkdirs()
        ant.withGroovyBuilder { "unzip"("src" to zip.get().asFile, "dest" to project) }
        val settings = project.resolve("settings.gradle")
        val public = Regex("""uri\("https://[^"]+/maven"\)""")
        check(public.containsMatchIn(settings.readText())) { "no Maven repository in the template's settings.gradle" }
        settings.writeText(settings.readText().replace(public, "uri(\"${repo.toURI()}\")"))
        val cmd = if (windows) listOf("cmd", "/c", project.resolve("gradlew.bat").absolutePath)
            else listOf("sh", project.resolve("gradlew").absolutePath)
        val p = ProcessBuilder(cmd + listOf("build", "--no-daemon", "--stacktrace", "-Pstein.sdkJar=${sdk.get().asFile}"))
            .directory(project).redirectErrorStream(true)
        p.environment()["STEIN_SDK_HOME"] = home.get().asFile.absolutePath
        p.environment()["JAVA_HOME"] = java.get().metadata.installationPath.asFile.absolutePath
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "the template's gradlew build failed:\n$out" }
        val mod = project.resolve("build/libs/ExampleMod.steinmod")
        check(mod.isFile) { "gradlew build made no $mod:\n$out" }
        ZipFile(mod).use { z ->
            val names = z.entries().toList().map { it.name }
            for (want in listOf("steinmod.json", "com/example/examplemod/ExampleMod.class")) {
                check(want in names) { "$mod has no $want: $names" }
            }
        }
        logger.lifecycle("template built by its Gradle wrapper with the plugin: $mod")
    }
}

spotless {
    // LF everywhere, as committed (.editorconfig); Windows checkouts too.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target("src/**/*.java", "template/src/**/*.java", "examples/*/src/**/*.java", "test/*/src/**/*.java",
            "gradle-plugin/java/**/*.java")
        googleJavaFormat(libs.versions.google.java.format.get()).aosp().reorderImports(true)
    }
}
