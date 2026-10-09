/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code plugins { id 'dev.xavier.stein.mod' }}: a Gradle project that produces a .steinmod.
 *
 * <ul>
 *   <li>java with toolchain 25 (the game's version), compiling with --release 25;
 *   <li>compileOnly: the jars that "stein-sdk.jar classpath &lt;project&gt;" prints (the game with
 *       readable names, with the "access" from steinmod.json, the API and the game's libraries) and
 *       the jars in libs/. Resolved only when someone asks — compileJava or the IDE on import; if
 *       the setup has not been done yet, it is done there (once per machine);
 *   <li>steinSetup, steinmod (in assemble, and therefore in build) and installMod.
 * </ul>
 *
 * <p>The plain Java jar is turned off: with readable names it does not run in the game, and next to
 * the .steinmod it would only confuse whoever looks for what to put in the mods folder.
 */
public class SteinPlugin implements Plugin<Project> {

    private static final int RELEASE = 25;

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        SteinExtension stein = createExtension(project);
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        configureJava(project, java);
        JavaToolchainService toolchains =
                project.getExtensions().getByType(JavaToolchainService.class);
        Provider<RegularFile> javaExe =
                toolchains.launcherFor(java.getToolchain()).map(JavaLauncher::getExecutablePath);
        Provider<RegularFile> sdkJar = sdkJarProvider(project, stein);

        TaskProvider<SteinSetup> setup = registerSetup(project, stein, sdkJar, javaExe);
        configureCompileClasspath(project, stein, sdkJar, javaExe, setup);
        TaskProvider<SteinPack> pack = registerPack(project, java, stein, sdkJar, javaExe);
        project.getTasks().named("assemble", t -> t.dependsOn(pack));
        project.getTasks().named(JavaPlugin.JAR_TASK_NAME, t -> t.setEnabled(false));
        registerInstall(project, stein, pack);
    }

    /** The {@code stein { }} block, with its conventions. */
    private static SteinExtension createExtension(Project project) {
        SteinExtension stein = project.getExtensions().create("stein", SteinExtension.class);
        stein.getSdkVersion().convention(SdkTool.pluginVersion());
        stein.getSdkHome()
                .convention(project.getLayout().dir(project.provider(SdkTool::defaultHome)));
        Object localJar = project.findProperty("stein.sdkJar");
        if (localJar != null) {
            File local = project.file(localJar);
            stein.getSdkJar().convention(project.getLayout().file(project.provider(() -> local)));
        }
        return stein;
    }

    /** Toolchain 25 and --release 25 / UTF-8 for every JavaCompile. */
    private static void configureJava(Project project, JavaPluginExtension java) {
        java.getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(RELEASE));
        project.getTasks()
                .withType(JavaCompile.class)
                .configureEach(
                        c -> {
                            c.getOptions().getRelease().convention(RELEASE);
                            c.getOptions().setEncoding("UTF-8");
                        });
    }

    /** The stein-sdk.jar: the one in {@code stein.sdkJar}, or the requested version's download. */
    private static Provider<RegularFile> sdkJarProvider(Project project, SteinExtension stein) {
        File gradleHome = project.getGradle().getGradleUserHomeDir();
        boolean offline = project.getGradle().getStartParameter().isOffline();
        return stein.getSdkJar()
                .orElse(
                        project.getLayout()
                                .file(
                                        stein.getSdkVersion()
                                                .map(
                                                        v ->
                                                                SdkTool.download(
                                                                        gradleHome, v, offline))));
    }

    private static TaskProvider<SteinSetup> registerSetup(
            Project project,
            SteinExtension stein,
            Provider<RegularFile> sdkJar,
            Provider<RegularFile> javaExe) {
        return project.getTasks()
                .register(
                        "steinSetup",
                        SteinSetup.class,
                        t -> {
                            t.getSdkJar().set(sdkJar);
                            t.getJavaExecutable().set(javaExe);
                            t.getSdkHome().set(stein.getSdkHome());
                            t.getMinecraftDir().set(stein.getMinecraftDir());
                        });
    }

    /** compileOnly: the SDK's classpath (SdkClasspath) and the jars in libs/. */
    private static void configureCompileClasspath(
            Project project,
            SteinExtension stein,
            Provider<RegularFile> sdkJar,
            Provider<RegularFile> javaExe,
            TaskProvider<SteinSetup> setup) {
        File projectDir = project.getProjectDir();
        ConfigurableFileCollection cp = project.getObjects().fileCollection();
        cp.from(new CachedClasspath(project.getProviders(), stein, sdkJar, javaExe, projectDir));
        cp.builtBy(setup);
        project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, cp);
        // As in "java -jar stein-sdk.jar build": the jars in libs/ (another mod's API) are only
        // for compiling.
        project.getDependencies()
                .add(
                        JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                        project.fileTree(new File(projectDir, "libs"), t -> t.include("*.jar")));
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, t -> t.dependsOn(setup));
    }

    private static TaskProvider<SteinPack> registerPack(
            Project project,
            JavaPluginExtension java,
            SteinExtension stein,
            Provider<RegularFile> sdkJar,
            Provider<RegularFile> javaExe) {
        TaskProvider<JavaCompile> compile =
                project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class);
        TaskProvider<ProcessResources> resources =
                project.getTasks()
                        .named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, ProcessResources.class);
        FileCollection resourceDirs =
                java.getSourceSets()
                        .getByName(SourceSet.MAIN_SOURCE_SET_NAME)
                        .getResources()
                        .getSourceDirectories();
        String projectName = project.getName();
        Provider<String> fileName =
                project.provider(() -> SdkTool.modFileName(manifest(resourceDirs), projectName));

        return project.getTasks()
                .register(
                        "steinmod",
                        SteinPack.class,
                        t -> {
                            t.getSdkJar().set(sdkJar);
                            t.getJavaExecutable().set(javaExe);
                            t.getSdkHome().set(stein.getSdkHome());
                            t.getClassesDir()
                                    .set(compile.flatMap(JavaCompile::getDestinationDirectory));
                            t.getResourcesDir()
                                    .set(
                                            project.getLayout()
                                                    .dir(
                                                            resources.map(
                                                                    ProcessResources
                                                                            ::getDestinationDir)));
                            t.getOutputFile()
                                    .set(
                                            project.getLayout()
                                                    .getBuildDirectory()
                                                    .file(fileName.map(n -> "libs/" + n)));
                            t.dependsOn(JavaPlugin.CLASSES_TASK_NAME);
                        });
    }

    private static void registerInstall(
            Project project, SteinExtension stein, TaskProvider<SteinPack> pack) {
        project.getTasks()
                .register(
                        "installMod",
                        Copy.class,
                        t -> {
                            t.setGroup("stein");
                            t.setDescription(
                                    "Builds the mod and copies it into the mods folder of"
                                            + " .minecraft.");
                            t.from(pack.flatMap(SteinPack::getOutputFile));
                            t.into(modsDir(project, stein));
                        });
    }

    /** The mods folder of .minecraft: the configured one, or the default installation's. */
    private static Provider<File> modsDir(Project project, SteinExtension stein) {
        Provider<File> fallback = project.provider(SdkTool::defaultMinecraft);
        return stein.getMinecraftDir()
                .orElse(project.getLayout().dir(fallback))
                .map(d -> d.dir("mods"))
                .map(Directory::getAsFile);
    }

    /** The steinmod.json from the sources (src/main/resources), where the file name comes from. */
    private static File manifest(FileCollection resourceDirs) {
        return resourceDirs.getFiles().stream()
                .map(d -> new File(d, "steinmod.json"))
                .filter(File::isFile)
                .findFirst()
                .orElse(null);
    }

    /**
     * The SDK classpath, asked of the SDK itself (SdkClasspath). Read once per build: the IDE and
     * compileJava resolve more than once, and each read would be one more java process. The
     * parameters go already resolved (files, not providers): the configuration cache checks the
     * ValueSource again on every build, and by then the project's toolchain no longer exists.
     */
    private static final class CachedClasspath implements Callable<List<File>> {
        private final ProviderFactory providers;
        private final SteinExtension stein;
        private final Provider<RegularFile> sdkJar;
        private final Provider<RegularFile> javaExe;
        private final File projectDir;
        private List<File> cached;

        CachedClasspath(
                ProviderFactory providers,
                SteinExtension stein,
                Provider<RegularFile> sdkJar,
                Provider<RegularFile> javaExe,
                File projectDir) {
            this.providers = providers;
            this.stein = stein;
            this.sdkJar = sdkJar;
            this.javaExe = javaExe;
            this.projectDir = projectDir;
        }

        @Override
        public synchronized List<File> call() {
            if (cached == null) {
                RegularFile jar = sdkJar.get();
                RegularFile javaFile = javaExe.get();
                Directory home = stein.getSdkHome().get();
                Directory mc = stein.getMinecraftDir().getOrNull();
                cached =
                        providers
                                .of(
                                        SdkClasspath.class,
                                        s -> {
                                            s.getParameters().getSdkJar().set(jar);
                                            s.getParameters().getJavaExecutable().set(javaFile);
                                            s.getParameters().getSdkHome().set(home);
                                            s.getParameters().getMinecraftDir().set(mc);
                                            s.getParameters().getProjectDir().set(projectDir);
                                        })
                                .get();
            }
            return cached;
        }
    }
}
