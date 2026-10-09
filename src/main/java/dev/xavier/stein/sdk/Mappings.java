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
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * The game's names in both directions: obfuscated (the player's jar) and readable (MCP stable 22,
 * what the mod writes).
 *
 * <p>It comes from MCP's joined.srg (obfuscated &lt;-&gt; SRG) and the stable mapping CSVs (SRG
 * &lt;-&gt; readable), which the {@link Sdk} downloads on the machine of whoever writes the mod —
 * none of this ships inside the SDK.
 *
 * <p>Both directions follow the hierarchy: javac writes the call with the type the code used
 * ({@code player.getName()} comes out as EntityPlayerSP.getName, which is declared in Entity), so a
 * member that is not in the cited class is looked up in its superclasses and interfaces.
 */
final class Mappings {

    /** Obfuscated class -> readable, and the reverse. */
    private final Map<String, String> cls = new HashMap<>();

    private final Map<String, String> clsBack = new HashMap<>();

    /** Obfuscated "owner.name" -> readable name; and "readableOwner.name" -> obfuscated. */
    private final Map<String, String> fields = new HashMap<>();

    private final Map<String, String> fieldsBack = new HashMap<>();

    /**
     * Obfuscated "owner.name(descriptor)" -> readable name; and the same, readable, -> obfuscated.
     */
    private final Map<String, String> methods = new HashMap<>();

    private final Map<String, String> methodsBack = new HashMap<>();

    /**
     * The same readable entries as fieldsBack and methodsBack -> SRG name (the build of the
     * official mods, which goes through SRG).
     */
    private final Map<String, String> fieldsSrg = new HashMap<>();

    private final Map<String, String> methodsSrg = new HashMap<>();

    /**
     * @param srg MCP's joined.srg for 1.8.9
     * @param fieldsCsv fields.csv from mcp_stable (searge,name,...)
     * @param methodsCsv methods.csv from mcp_stable
     */
    static Mappings load(Path srg, Path fieldsCsv, Path methodsCsv) throws IOException {
        Map<String, String> fieldNames = csv(fieldsCsv);
        Map<String, String> methodNames = csv(methodsCsv);
        Mappings m = new Mappings();
        List<String> lines = Files.readAllLines(srg, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.startsWith("CL: ")) {
                String[] p = line.substring(4).trim().split(" ");
                m.cls.put(p[0], p[1]);
                m.clsBack.put(p[1], p[0]);
            }
        }
        for (String line : lines) {
            if (line.startsWith("FD: ")) {
                String[] p = line.substring(4).trim().split(" ");
                String obfOwner = owner(p[0]);
                String obfName = member(p[0]);
                String srgName = member(p[1]);
                String name = fieldNames.getOrDefault(srgName, srgName);
                m.fields.put(obfOwner + "." + obfName, name);
                m.fieldsBack.put(owner(p[1]) + "." + name, obfName);
                m.fieldsSrg.put(owner(p[1]) + "." + name, srgName);
            } else if (line.startsWith("MD: ")) {
                String[] p = line.substring(4).trim().split(" ");
                String obfOwner = owner(p[0]);
                String obfName = member(p[0]);
                String srgName = member(p[2]);
                String name = methodNames.getOrDefault(srgName, srgName);
                m.methods.put(obfOwner + "." + obfName + p[1], name);
                m.methodsBack.put(owner(p[2]) + "." + name + p[3], obfName);
                m.methodsSrg.put(owner(p[2]) + "." + name + p[3], srgName);
            }
        }
        return m;
    }

    private static String owner(String s) {
        return s.substring(0, s.lastIndexOf('/'));
    }

    private static String member(String s) {
        return s.substring(s.lastIndexOf('/') + 1);
    }

    private static Map<String, String> csv(Path file) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split(",", 3);
            if (p.length >= 2 && !p[0].equals("searge")) {
                out.put(p[0], p[1]);
            }
        }
        return out;
    }

    /** How many classes the mapping knows (for the setup message). */
    int classes() {
        return cls.size();
    }

    /** The readable name of this obfuscated class, or null. */
    String named(String obf) {
        return cls.get(obf);
    }

    /** The obfuscated class of this readable name, or null. */
    String obf(String named) {
        return clsBack.get(named);
    }

    // ---- hierarchy ----

    /** Superclass and interfaces of each class in a set of jars (read from the header only). */
    static final class Hierarchy {
        private final Map<String, String[]> supers = new HashMap<>();
        private final Map<String, Set<String>> fieldsOf = new HashMap<>();

        void add(byte[] classBytes) {
            ClassReader r = new ClassReader(classBytes);
            String[] itf = r.getInterfaces();
            String[] all = new String[itf.length + 1];
            all[0] = r.getSuperName();
            System.arraycopy(itf, 0, all, 1, itf.length);
            supers.put(r.getClassName(), all);
            Set<String> fields = new HashSet<>();
            r.accept(
                    new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public FieldVisitor visitField(
                                int access, String name, String desc, String sig, Object value) {
                            // Protected fields stay with javac's owner (see fieldOwner).
                            if ((access & Opcodes.ACC_PROTECTED) == 0) {
                                fields.add(name + ":" + desc);
                            }
                            return null;
                        }
                    },
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            fieldsOf.put(r.getClassName(), fields);
        }

        /**
         * The class that declares the field, searched from {@code owner} upwards; {@code owner} if
         * the field is protected (a subclass must name itself to reach it: JLS 6.6.2) or if none of
         * the known classes declares it.
         */
        String fieldOwner(String owner, String name, String desc) {
            String key = name + ":" + desc;
            for (String c : chain(owner)) {
                Set<String> f = fieldsOf.get(c);
                if (f != null && f.contains(key)) {
                    return c;
                }
            }
            return owner;
        }

        void addJar(Path jar) throws IOException {
            try (JarFile j = new JarFile(jar.toFile())) {
                for (JarEntry e : j.stream().toList()) {
                    if (e.getName().endsWith(".class")) {
                        try (InputStream in = j.getInputStream(e)) {
                            add(in.readAllBytes());
                        }
                    }
                }
            }
        }

        /** The class and all its supertypes, from it upwards (breadth-first). */
        List<String> chain(String name) {
            List<String> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>();
            todo.add(name);
            while (!todo.isEmpty()) {
                String c = todo.poll();
                if (c == null || !seen.add(c)) {
                    continue;
                }
                out.add(c);
                String[] s = supers.get(c);
                if (s != null) {
                    for (String x : s) {
                        if (x != null) {
                            todo.add(x);
                        }
                    }
                }
            }
            return out;
        }
    }

    // ---- the two directions ----

    /** Obfuscated -> readable (the game jar, for compiling). */
    Remapper toNamed(Hierarchy obf) {
        return fromNamed(obf, cls, fields, methods, false);
    }

    /**
     * Readable -> obfuscated (the mod, for the game). {@code named} has the classes of the compile
     * jar and the mod's own: a mod method that overrides a game one (drawScreen on a GuiScreen) is
     * found through the superclass and gets the obfuscated name.
     */
    Remapper toObf(Hierarchy named) {
        return fromNamed(named, clsBack, fieldsBack, methodsBack, true);
    }

    /**
     * Readable -> SRG, with the classes as they are (the class name in SRG is MCP's): the build of
     * the official mods, which are written with readable names and from there follow the usual SRG
     * path.
     */
    Remapper toSrg(Hierarchy named) {
        return fromNamed(named, Map.of(), fieldsSrg, methodsSrg, true);
    }

    /**
     * The remapper of the three directions: looks each member up in the class and its supertypes
     * (hierarchy). {@code lambdas} also renames lambdas that implement a game interface (the mod's
     * direction; the game jar's own lambdas are left as they are).
     */
    private static Remapper fromNamed(
            Hierarchy named,
            Map<String, String> classes,
            Map<String, String> fieldMap,
            Map<String, String> methodMap,
            boolean lambdas) {
        return new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                return classes.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapFieldName(String owner, String name, String desc) {
                for (String c : named.chain(owner)) {
                    String n = fieldMap.get(c + "." + name);
                    if (n != null) {
                        return n;
                    }
                }
                return name;
            }

            @Override
            public String mapMethodName(String owner, String name, String desc) {
                if (name.startsWith("<")) {
                    return name;
                }
                for (String c : named.chain(owner)) {
                    String n = methodMap.get(c + "." + name + desc);
                    if (n != null) {
                        return n;
                    }
                }
                return name;
            }

            /**
             * Lambda that implements a game interface: its method name belongs to the game too. The
             * interface is the descriptor's return type; the method is the first argument of the
             * LambdaMetafactory.
             */
            @Override
            public String mapInvokeDynamicMethodName(
                    String name, String desc, Handle bsm, Object... args) {
                if (lambdas
                        && bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        && args.length > 0
                        && args[0] instanceof Type sam) {
                    return mapMethodName(
                            Type.getReturnType(desc).getInternalName(), name, sam.getDescriptor());
                }
                return name;
            }
        };
    }

    /** Runs each class of the jar through the remapper; the rest of the jar goes as is. */
    static void remapJar(Path in, Path out, Remapper r) throws IOException {
        try (JarFile jar = new JarFile(in.toFile());
                JarOutputStream os = new JarOutputStream(Files.newOutputStream(out))) {
            Set<String> written = new HashSet<>();
            for (JarEntry e : jar.stream().toList()) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) {
                    continue; // Mojang's manifest and signatures: not valid for the copy
                }
                byte[] data;
                try (InputStream is = jar.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                String name = e.getName();
                if (name.endsWith(".class")) {
                    name = r.map(new ClassReader(data).getClassName()) + ".class";
                    data = remap(data, r);
                }
                if (written.add(name)) {
                    os.putNextEntry(new JarEntry(name));
                    os.write(data);
                    os.closeEntry();
                }
            }
        }
    }

    /** One class through the remapper. */
    static byte[] remap(byte[] classBytes, Remapper r) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassRemapper(cw, r), 0);
        return cw.toByteArray();
    }

    /**
     * One compiled class (readable names) through the remapper, with every field instruction first
     * pointed at the class that declares the field. javac names the type of the expression ({@code
     * pos.y} on a BlockPos says BlockPos), and after obfuscation that type may have its own,
     * unrelated field with the same name and type: BlockPos's static {@code c} hides Vec3i's {@code
     * c} (y), and the JVM would read the static one. With the declaring class named, the reference
     * cannot be captured.
     */
    static byte[] remap(byte[] classBytes, Remapper r, Hierarchy named) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(
                new ClassVisitor(Opcodes.ASM9, new ClassRemapper(cw, r)) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        return mv == null
                                ? null
                                : new MethodVisitor(Opcodes.ASM9, mv) {
                                    @Override
                                    public void visitFieldInsn(
                                            int op, String owner, String field, String fdesc) {
                                        super.visitFieldInsn(
                                                op,
                                                named.fieldOwner(owner, field, fdesc),
                                                field,
                                                fdesc);
                                    }
                                };
                    }
                },
                0);
        return cw.toByteArray();
    }
}
