/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code stein { }} block of the mod's build.gradle.
 *
 * <pre>
 *   stein {
 *       sdkVersion = "x.y.z"                       // the stein-sdk.jar release (default: the plugin's)
 *       minecraftDir = file("D:/Games/.minecraft") // if .minecraft is not in the usual place
 *       sdkJar = file("../stein-sdk.jar")          // a local stein-sdk.jar, nothing downloaded
 *   }
 * </pre>
 */
public abstract class SteinExtension {

    /**
     * The stein-sdk.jar version, downloaded from the SDK releases ({@code SdkTool.RELEASES}, then
     * v&lt;version&gt;/stein-sdk.jar).
     */
    public abstract Property<String> getSdkVersion();

    /** The .minecraft: where the setup takes the game from, and where installMod copies to. */
    public abstract DirectoryProperty getMinecraftDir();

    /** A local stein-sdk.jar; with it nothing is downloaded (also -Pstein.sdkJar=path). */
    public abstract RegularFileProperty getSdkJar();

    /**
     * The SDK working folder (default: STEIN_SDK_HOME or ~/.stein-sdk), where the setup assembles
     * the game.
     */
    public abstract DirectoryProperty getSdkHome();
}
