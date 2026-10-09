/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.sdk;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game jar for COMPILING mods: fields and methods that the mod could not reach are left open.
 *
 * <p>With it the mod writes {@code overlay.header} (private in the game) without reflection. It
 * only serves javac: in the game the member stays as it was, and the Loader replaces each access
 * the JVM would refuse with an invokedynamic bound to the member (ModPatch and internal.Access).
 *
 * <p>What it opens and what stays:
 *
 * <ul>
 *   <li>fields: all public; instance fields lose final (Minecraft.session, for example, is replaced
 *       by the accounts mod). Static finals stay final — javac copies their constants, and must
 *       keep copying them;
 *   <li>private and package-private methods: public. Protected ones stay protected: opening them
 *       all would force the mod to override with public ({@code protected void keyTyped} would stop
 *       compiling). A protected method the mod wants to call from outside is requested in
 *       steinmod.json, and only that one opens, under the name it has in the compile jar: {@code
 *       "access": ["net/minecraft/client/gui/GuiPlayerTabOverlay.drawPing"]};
 *   <li>constructors and classes: as they are — a {@code new} on a closed class cannot be replaced
 *       in the game.
 * </ul>
 *
 * <p>It is the same piece in this repository's build (which runs it as a source file) and in the
 * SDK (stein-sdk.jar).
 *
 * <p>{@code --all} also opens every protected method: for code that only calls the game and never
 * overrides it (the Stein Bridge).
 *
 * <p>Usage: java -cp asm.jar OpenJar.java &lt;input.jar&gt; &lt;output.jar&gt; [--all | --access
 * steinmod.json]
 */
public final class OpenJar {

    private OpenJar() {}

    public static void main(String[] args) throws IOException {
        boolean all = args.length > 2 && args[2].equals("--all");
        Set<String> asked =
                args.length > 3 && args[2].equals("--access")
                        ? access(Paths.get(args[3]))
                        : Set.of();
        int opened = open(Paths.get(args[0]), Paths.get(args[1]), asked, all);
        System.out.println("jar for compiling mods: " + opened + " members opened -> " + args[1]);
    }

    /**
     * The protected methods that steinmod.json asks to open ("access"), as "class.name", with
     * slashes.
     */
    public static Set<String> access(Path manifest) throws IOException {
        Set<String> asked = new HashSet<>();
        Matcher list =
                Pattern.compile("\"access\"\\s*:\\s*\\[([^\\]]*)\\]")
                        .matcher(Files.readString(manifest));
        if (list.find()) {
            Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
            while (s.find()) {
                asked.add(s.group(1));
            }
        }
        return asked;
    }

    /** Writes to out the jar in with the members opened; returns how many it opened. */
    public static int open(Path in, Path out, Set<String> asked) throws IOException {
        return open(in, out, asked, false);
    }

    /** Like {@link #open(Path, Path, Set)}; with {@code all}, protected methods open too. */
    public static int open(Path in, Path out, Set<String> asked, boolean all) throws IOException {
        int opened = 0;
        try (JarFile jar = new JarFile(in.toFile());
                JarOutputStream os = new JarOutputStream(Files.newOutputStream(out))) {
            for (JarEntry e : jar.stream().toList()) {
                if (e.isDirectory()) {
                    continue;
                }
                byte[] data;
                try (InputStream is = jar.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                if (e.getName().endsWith(".class")) {
                    Opener opener = new Opener(asked, all);
                    data = opener.open(data);
                    opened += opener.opened;
                }
                os.putNextEntry(new JarEntry(e.getName()));
                os.write(data);
                os.closeEntry();
            }
        }
        return opened;
    }

    private static int makePublic(int access) {
        return access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
    }

    /** Opens the members of one class at a time and counts how many it changed. */
    private static final class Opener extends ClassVisitor {
        private final Set<String> asked;
        private final boolean all;
        int opened;
        private String className;

        Opener(Set<String> asked, boolean all) {
            super(Opcodes.ASM9);
            this.asked = asked;
            this.all = all;
        }

        byte[] open(byte[] data) {
            ClassReader cr = new ClassReader(data);
            ClassWriter writer = new ClassWriter(cr, 0);
            cv = writer;
            cr.accept(this, 0);
            return writer.toByteArray();
        }

        @Override
        public void visit(
                int version, int access, String name, String sig, String sup, String[] itf) {
            className = name;
            super.visit(version, access, name, sig, sup, itf);
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String desc, String sig, Object value) {
            int a = makePublic(access);
            if ((a & Opcodes.ACC_STATIC) == 0) {
                a &= ~Opcodes.ACC_FINAL;
            }
            if (a != access) {
                opened++;
            }
            return super.visitField(a, name, desc, sig, value);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String desc, String sig, String[] ex) {
            int a = access;
            boolean ctor = name.equals("<init>") || name.equals("<clinit>");
            boolean wanted =
                    (access & Opcodes.ACC_PROTECTED) != 0
                            && (all || asked.contains(className + "." + name));
            if (!ctor && ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0 || wanted)) {
                a = makePublic(access);
                opened++;
            }
            return super.visitMethod(a, name, desc, sig, ex);
        }
    }
}
