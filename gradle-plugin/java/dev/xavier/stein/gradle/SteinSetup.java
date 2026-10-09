/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code steinSetup}: "java -jar stein-sdk.jar setup" — the game with readable names, on the
 * machine of whoever is building. Once per machine: with the setup already done (the libraries.txt
 * in the SDK folder), the task is skipped.
 */
public abstract class SteinSetup extends SdkTask {

    /** The .minecraft the game comes from (--mc); empty means the default one. */
    @Internal
    public abstract DirectoryProperty getMinecraftDir();

    public SteinSetup() {
        setGroup("stein");
        setDescription(
                "Prepares Minecraft 1.8.9 with readable names for compiling (once per machine).");
        onlyIf(
                "the Stein SDK is not set up yet",
                t -> !SdkTool.setupDone(((SteinSetup) t).getSdkHome().get().getAsFile()).isFile());
    }

    @TaskAction
    public void setup() {
        sdk(
                SdkTool.setupArgs(
                        getMinecraftDir().isPresent()
                                ? getMinecraftDir().get().getAsFile()
                                : null));
    }
}
