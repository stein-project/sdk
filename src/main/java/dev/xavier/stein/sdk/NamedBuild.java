/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.sdk;

import org.objectweb.asm.commons.Remapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * The readable names in the Stein build: the Loader and the official mods are written like an
 * outside mod (MCP stable 22, {@code Minecraft.getMinecraft()}), and from here they follow the
 * usual SRG path — the same {@link Mappings} as the SDK, so what compiles in the SDK compiles the
 * same here.
 *
 * <pre>
 * game &lt;game.jar&gt; &lt;joined.srg&gt; &lt;fields.csv&gt; &lt;methods.csv&gt;
 * &lt;output.jar&gt;
 *         the game jar with readable names, to compile against
 * tosrg &lt;joined.srg&gt; &lt;fields.csv&gt; &lt;methods.csv&gt; &lt;readable-game.jar&gt;
 * &lt;input&gt; &lt;output&gt; [classpath...]
 * the compiled classes (folder), with the game's members under SRG names; the classpath (folders or
 * jars) joins
 *         the hierarchy, to find the game method that a class from there overrides
 * </pre>
 *
 * <p>Usage: {@code java -cp asm.jar;asm-commons.jar
 * sdk/src/main/java/dev/xavier/stein/sdk/NamedBuild.java ...}
 */
public final class NamedBuild {

    private NamedBuild() {}

    public static void main(String[] args) throws IOException {
        if (args.length >= 6 && args[0].equals("game")) {
            game(args);
        } else if (args.length >= 7 && args[0].equals("tosrg")) {
            toSrg(args);
        } else {
            System.err.println(
                    "usage: NamedBuild game <game.jar> <srg> <fields.csv> <methods.csv> <out.jar>");
            System.err.println(
                    "     NamedBuild tosrg <srg> <fields.csv> <methods.csv> <readable-game.jar>"
                            + " <input> <output> [classpath...]");
            System.exit(2);
        }
    }

    /** The {@code game} subcommand: the obfuscated game jar to readable names. */
    private static void game(String[] args) throws IOException {
        Mappings map = Mappings.load(Paths.get(args[2]), Paths.get(args[3]), Paths.get(args[4]));
        Mappings.Hierarchy obf = new Mappings.Hierarchy();
        obf.addJar(Paths.get(args[1]));
        Mappings.remapJar(Paths.get(args[1]), Paths.get(args[5]), map.toNamed(obf));
    }

    /** The {@code tosrg} subcommand: compiled classes with readable names to SRG names. */
    private static void toSrg(String[] args) throws IOException {
        Mappings map = Mappings.load(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
        Mappings.Hierarchy named = new Mappings.Hierarchy();
        named.addJar(Paths.get(args[4]));
        Path in = Paths.get(args[5]);
        Path out = Paths.get(args[6]);
        for (int i = 7; i < args.length; i++) {
            for (String part :
                    args[i].split(File.pathSeparator)) { // an argument can be a whole classpath
                Path p = Paths.get(part);
                if (Files.isDirectory(p)) {
                    addDir(named, p);
                } else if (Files.isRegularFile(p)) {
                    named.addJar(p);
                }
            }
        }
        addDir(named, in);
        Remapper remapper = map.toSrg(named);
        int n = 0;
        for (Path p : files(in)) {
            Path target = out.resolve(in.relativize(p).toString());
            Files.createDirectories(target.getParent());
            byte[] b = Files.readAllBytes(p);
            boolean isClass = p.toString().endsWith(".class");
            Files.write(target, isClass ? Mappings.remap(b, remapper, named) : b);
            n++;
        }
        System.out.println("readable names -> SRG: " + n + " files");
    }

    private static void addDir(Mappings.Hierarchy h, Path dir) throws IOException {
        for (Path p : files(dir)) {
            if (p.toString().endsWith(".class")) {
                h.add(Files.readAllBytes(p));
            }
        }
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }
}
