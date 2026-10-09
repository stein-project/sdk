/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.util.List;

/**
 * {@code steinmod}: "java -jar stein-sdk.jar pack" over what Gradle compiled — translates the names
 * to the game's, checks every reference to the game and packages it with the resources in
 * build/libs/&lt;Name&gt;.steinmod.
 */
public abstract class SteinPack extends SdkTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getClassesDir();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getResourcesDir();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    public SteinPack() {
        setGroup("stein");
        setDescription(
                "Translates, checks and packs the compiled mod into build/libs/<Name>.steinmod.");
    }

    @TaskAction
    public void pack() {
        sdk(
                List.of(
                        "pack",
                        getClassesDir().get().getAsFile().getAbsolutePath(),
                        getResourcesDir().get().getAsFile().getAbsolutePath(),
                        getOutputFile().get().getAsFile().getAbsolutePath()));
    }
}
