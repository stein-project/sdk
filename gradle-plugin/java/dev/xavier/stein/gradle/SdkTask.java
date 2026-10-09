/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.ExecOperations;

import java.io.File;
import java.util.List;

import javax.inject.Inject;

/** Task that runs stein-sdk.jar: the jar, the Java 25 that runs it and the SDK working folder. */
public abstract class SdkTask extends DefaultTask {

    /**
     * The stein-sdk.jar (downloaded, or the one from {@code stein.sdkJar}): switching SDKs rebuilds
     * the .steinmod.
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSdkJar();

    /** The project toolchain's java (25): the SDK does not run on an older Java. */
    @Internal
    public abstract RegularFileProperty getJavaExecutable();

    @Internal
    public abstract DirectoryProperty getSdkHome();

    @Inject
    protected abstract ExecOperations getExec();

    /** Runs the SDK with the given args and shows what it printed. */
    protected String sdk(List<String> args) {
        File java = getJavaExecutable().get().getAsFile();
        return SdkTool.run(
                getExec(),
                java,
                getSdkJar().get().getAsFile(),
                getSdkHome().get().getAsFile(),
                args,
                getLogger());
    }
}
