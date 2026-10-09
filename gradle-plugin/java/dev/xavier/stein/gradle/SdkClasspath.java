/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.process.ExecOperations;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * The jars needed to compile the mod, requested from the SDK ("stein-sdk.jar classpath
 * &lt;project&gt;"). It is a ValueSource because it is read when Gradle builds the classpath (or
 * when the IDE imports the project), before any task: this is the only way Gradle accepts an
 * external process at that point.
 *
 * <p>If the setup has not been done yet, it is done here — this is what lets the IDE import the
 * project on the first try, without running steinSetup first. It takes a while the first time (it
 * downloads the MCP mappings), and that is all.
 */
public abstract class SdkClasspath implements ValueSource<ArrayList<File>, SdkClasspath.Params> {

    public interface Params extends ValueSourceParameters {
        RegularFileProperty getSdkJar();

        RegularFileProperty getJavaExecutable();

        DirectoryProperty getSdkHome();

        DirectoryProperty getMinecraftDir();

        DirectoryProperty getProjectDir();
    }

    @Inject
    protected abstract ExecOperations getExec();

    @Override
    public ArrayList<File> obtain() {
        Params p = getParameters();
        File home = p.getSdkHome().get().getAsFile();
        File jar = p.getSdkJar().get().getAsFile();
        File java = p.getJavaExecutable().get().getAsFile();
        Logger log = Logging.getLogger(SdkClasspath.class);
        if (!SdkTool.setupDone(home).isFile()) {
            log.lifecycle(
                    "Stein SDK: preparing Minecraft "
                            + SdkTool.MC_VERSION
                            + " for compiling (once per machine)...");
            File mc =
                    p.getMinecraftDir().isPresent() ? p.getMinecraftDir().get().getAsFile() : null;
            SdkTool.run(getExec(), java, jar, home, SdkTool.setupArgs(mc), log);
        }
        String out =
                SdkTool.run(
                        getExec(),
                        java,
                        jar,
                        home,
                        List.of("classpath", p.getProjectDir().get().getAsFile().getAbsolutePath()),
                        null);
        return new ArrayList<>(SdkTool.jars(out));
    }
}
