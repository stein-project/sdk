/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.sdk;

import dev.xavier.stein.sdk.Sdk.SdkException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Checks that every reference to the game in translated mod classes exists in the real game: a
 * class that stayed with a readable name (outside the mapping) or a member the game does not have
 * stops here, with the name the developer wrote.
 */
final class ReferenceCheck extends ClassVisitor {

    private final String self;
    private final Map<String, Set<String>> members;
    private final Mappings.Hierarchy obf;
    private final Mappings map;
    private final List<String> errors;

    private ReferenceCheck(
            String self,
            Map<String, Set<String>> members,
            Mappings.Hierarchy obf,
            Mappings map,
            List<String> errors) {
        super(Opcodes.ASM9);
        this.self = self;
        this.members = members;
        this.obf = obf;
        this.map = map;
        this.errors = errors;
    }

    /**
     * Throws if any class (entry name to bytes) refers to something the game's {@code client} jar
     * does not have.
     */
    static void check(Map<String, byte[]> classes, Path client, Mappings map)
            throws IOException, SdkException {
        Mappings.Hierarchy obf = new Mappings.Hierarchy();
        Map<String, Set<String>> members = readGameMembers(client, obf);
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, byte[]> c : classes.entrySet()) {
            new ClassReader(c.getValue())
                    .accept(new ReferenceCheck(c.getKey(), members, obf, map, errors), 0);
        }
        if (!errors.isEmpty()) {
            errors.stream().distinct().forEach(e -> System.err.println("  " + e));
            throw new SdkException(errors.size() + " reference(s) to the game that do not exist");
        }
    }

    /**
     * Every class of the game jar to its fields ("name:desc") and methods ("namedesc"); fills obf.
     */
    private static Map<String, Set<String>> readGameMembers(Path client, Mappings.Hierarchy obf)
            throws IOException {
        Map<String, Set<String>> members = new HashMap<>();
        try (JarFile jar = new JarFile(client.toFile())) {
            for (JarEntry e : jar.stream().toList()) {
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] b;
                try (InputStream in = jar.getInputStream(e)) {
                    b = in.readAllBytes();
                }
                obf.add(b);
                Set<String> m = new HashSet<>();
                ClassReader r = new ClassReader(b);
                r.accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    Object value) {
                                m.add(name + ":" + desc);
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    String[] exceptions) {
                                m.add(name + desc);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE);
                members.put(r.getClassName(), m);
            }
        }
        return members;
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String desc, String signature, String[] exceptions) {
        return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitFieldInsn(int op, String owner, String name, String desc) {
                ref(owner, name + ":" + desc, name);
            }

            @Override
            public void visitMethodInsn(
                    int op, String owner, String name, String desc, boolean itf) {
                ref(owner, name + desc, name);
            }

            @Override
            public void visitTypeInsn(int op, String type) {
                ref(type, null, null);
            }
        };
    }

    private void ref(String owner, String key, String name) {
        if (owner.startsWith("[")) {
            return;
        }
        if (owner.startsWith("net/minecraft/") && map.obf(owner) == null) {
            errors.add(
                    self
                            + ": "
                            + owner.replace('/', '.')
                            + " is not a Minecraft "
                            + Sdk.MC_VERSION
                            + " class");
            return;
        }
        if (key == null || !members.containsKey(owner)) {
            return; // mod, JDK or library class
        }
        for (String k : obf.chain(owner)) {
            Set<String> m = members.get(k);
            if (m == null || m.contains(key)) {
                // Found, or went up to the JDK (Object.toString...).
                return;
            }
        }
        String readable = map.named(owner);
        errors.add(
                self
                        + ": "
                        + (readable == null ? owner : readable).replace('/', '.')
                        + "."
                        + name
                        + " does not exist in Minecraft "
                        + Sdk.MC_VERSION);
    }
}
