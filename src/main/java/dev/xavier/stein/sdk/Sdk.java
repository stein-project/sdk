/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Part of the Stein SDK, licensed under the MIT License (see LICENSE).
 */

package dev.xavier.stein.sdk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Stein SDK: what mod authors need to compile a .steinmod, with nothing from the machine here.
 *
 * <pre>
 *   java -jar stein-sdk.jar setup     [--mc folder]  prepares the game for compiling (once)
 *   java -jar stein-sdk.jar build     [project]      compiles the mod -&gt; build/&lt;Name&gt;.steinmod
 *   java -jar stein-sdk.jar install   [project]      builds and copies it to the game's mods folder
 *   java -jar stein-sdk.jar classpath [project]      the jars for the IDE (with the project's "access")
 *   java -jar stein-sdk.jar pack      &lt;classes&gt; &lt;resources&gt; &lt;out.steinmod&gt;
 *                                                    translates, checks and packs compiled classes
 * </pre>
 *
 * <p>The build is the compile followed by the pack. Separately, they serve the Gradle plugin
 * (gradle-plugin): Gradle compiles with the classpath that {@code classpath} prints, and {@code
 * pack} does the rest — the same code as the build.
 *
 * <p>The setup uses the developer's own Minecraft 1.8.9 (or downloads it from Mojang's server, as
 * the launcher does) and the MCP mappings (from Forge's maven), and assembles ON THEIR MACHINE the
 * game with readable names and open members ({@link OpenJar}). Nothing from Mojang or MCP goes
 * inside this jar. The build compiles, translates the names back to the game's ({@link Mappings})
 * and checks that every reference to the game exists — the error shows up here, not in the game.
 *
 * <p>Messages in English: they are for mod authors, from anywhere.
 */
public final class Sdk {

    /**
     * SDK version (the release tooling reads it from here). The API bundled inside is versioned
     * separately.
     */
    public static final String VERSION = "0.2.3";

    static final String MC_VERSION = "1.8.9";
    private static final String MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String MCP_SRG =
            "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.8.9/mcp-1.8.9-srg.zip";
    private static final String MCP_STABLE =
            "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/22-1.8.9/"
                    + "mcp_stable-22-1.8.9.zip";

    /** Java version of the mods: the game's. */
    private static final int RELEASE = 25;

    private final Path home;
    private final Path mc;

    private Sdk(Path home, Path mc) {
        this.home = home;
        this.mc = mc;
    }

    public static void main(String[] args) {
        if (Runtime.version().feature() < RELEASE) {
            fail(
                    "Java "
                            + RELEASE
                            + " or newer is required (this is "
                            + Runtime.version().feature()
                            + ")");
        }
        String cmd = args.length == 0 ? "help" : args[0];
        Path mcDir = null;
        List<Path> paths = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--mc") && i + 1 < args.length) {
                mcDir = Paths.get(args[++i]);
            } else {
                paths.add(Paths.get(args[i]).toAbsolutePath());
            }
        }
        Path project =
                paths.isEmpty() ? Paths.get("").toAbsolutePath() : paths.get(paths.size() - 1);
        Path home = resolveHome();
        Sdk sdk = new Sdk(home, mcDir != null ? mcDir : defaultMinecraft());
        try {
            switch (cmd) {
                case "setup" -> sdk.setup();
                case "build" -> sdk.build(project);
                case "install" -> sdk.install(sdk.build(project));
                case "classpath" ->
                        sdk.classpath(paths.isEmpty() ? null : project)
                                .forEach(System.out::println);
                case "pack" -> {
                    if (paths.size() != 3) {
                        throw new SdkException(
                                "usage: pack <classesDir> <resourcesDir> <out.steinmod>");
                    }
                    int n = sdk.pack(paths.get(0), paths.get(1), paths.get(2));
                    System.out.println("packed " + paths.get(2) + " (" + n + " classes)");
                }
                default -> usage();
            }
        } catch (SdkException e) {
            fail(e.getMessage());
        } catch (IOException | InterruptedException e) {
            fail(e.toString());
        }
    }

    /** The SDK's working folder for this Minecraft version (STEIN_SDK_HOME or ~/.stein-sdk). */
    private static Path resolveHome() {
        String env = System.getenv("STEIN_SDK_HOME");
        return (env != null
                        ? Paths.get(env)
                        : Paths.get(System.getProperty("user.home"), ".stein-sdk"))
                .resolve(MC_VERSION);
    }

    private static void usage() {
        System.out.println(
                "Stein SDK "
                        + VERSION
                        + " (experimental API) — build Stein Loader mods for Minecraft "
                        + MC_VERSION);
        System.out.println("  setup [--mc dir]     prepare the game for compiling (once)");
        System.out.println("  build [project]      compile the mod -> build/<Name>.steinmod");
        System.out.println("  install [project]    build, then copy into the game's mods folder");
        System.out.println(
                "  classpath [project]  the jars to compile against (with the project's"
                        + " \"access\")");
        System.out.println("  pack <classes> <resources> <out.steinmod>");
        System.out.println(
                "                       translate, check and pack classes compiled"
                        + " elsewhere (Gradle)");
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.exit(1);
    }

    /** Error for whoever uses the SDK: the message is enough, no stack trace. */
    static final class SdkException extends Exception {
        SdkException(String message) {
            super(message);
        }
    }

    private static Path defaultMinecraft() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String user = System.getProperty("user.home");
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            return Paths.get(appdata != null ? appdata : user, ".minecraft");
        }
        if (os.contains("mac")) {
            return Paths.get(user, "Library", "Application Support", "minecraft");
        }
        return Paths.get(user, ".minecraft");
    }

    // ---- setup ----

    private Path named() {
        return home.resolve("minecraft-named.jar");
    }

    private Path namedOpen() {
        return home.resolve("minecraft-named-open.jar");
    }

    private void setup() throws IOException, InterruptedException, SdkException {
        Files.createDirectories(home);
        String versionJson = versionJson();
        Path client = clientJar(versionJson);
        List<Path> libs = libraries(versionJson);
        Path srg = home.resolve("mcp/joined.srg");
        Path fieldsCsv = home.resolve("mcp/fields.csv");
        Path methodsCsv = home.resolve("mcp/methods.csv");
        if (!Files.isRegularFile(srg)
                || !Files.isRegularFile(fieldsCsv)
                || !Files.isRegularFile(methodsCsv)) {
            Files.createDirectories(srg.getParent());
            Path srgZip = download(MCP_SRG, home.resolve("mcp/mcp-srg.zip"), sha1Of(MCP_SRG));
            Path stableZip =
                    download(MCP_STABLE, home.resolve("mcp/mcp-stable.zip"), sha1Of(MCP_STABLE));
            unzip(srgZip, "joined.srg", srg);
            unzip(stableZip, "fields.csv", fieldsCsv);
            unzip(stableZip, "methods.csv", methodsCsv);
        }
        System.out.println("mappings: MCP " + MC_VERSION + " (srg) + stable_22");

        Mappings map = Mappings.load(srg, fieldsCsv, methodsCsv);
        Mappings.Hierarchy obf = new Mappings.Hierarchy();
        obf.addJar(client);
        Mappings.remapJar(client, named(), map.toNamed(obf));
        int opened = OpenJar.open(named(), namedOpen(), Set.of());
        System.out.println(
                "game remapped to readable names ("
                        + map.classes()
                        + " classes), "
                        + opened
                        + " members opened for compiling");

        installApi();
        Files.writeString(
                home.resolve("libraries.txt"),
                String.join("\n", libs.stream().map(Path::toString).toList()));
        Files.writeString(home.resolve("client.txt"), client.toString());
        System.out.println("ready: " + home);
        System.out.println("IDE: add these jars as libraries (java -jar stein-sdk.jar classpath):");
        classpath(null).forEach(p -> System.out.println("  " + p));
    }

    private void requireSetup() throws SdkException {
        if (!Files.isRegularFile(home.resolve("libraries.txt"))) {
            throw new SdkException("run 'java -jar stein-sdk.jar setup' first");
        }
    }

    private Path versionFile(String extension) {
        return mc.resolve("versions/" + MC_VERSION + "/" + MC_VERSION + "." + extension);
    }

    /** The manifest entry of a version: its url and sha1. */
    private static Pattern versionEntry(String id) {
        return Pattern.compile(
                "\\{\"id\":\\s*\""
                        + Pattern.quote(id)
                        + "\"[^}]*?\"url\":\\s*\"([^\"]+)\""
                        + "[^}]*?\"sha1\":\\s*\"([0-9a-f]{40})\"");
    }

    /** The 1.8.9.json: the installation's, or Mojang's server's. */
    private String versionJson() throws IOException, InterruptedException, SdkException {
        Path local = versionFile("json");
        if (Files.isRegularFile(local)) {
            return Files.readString(local);
        }
        Path cached = home.resolve(MC_VERSION + ".json");
        if (!Files.isRegularFile(cached)) {
            String manifest = new String(fetch(MANIFEST), StandardCharsets.UTF_8);
            Matcher m = versionEntry(MC_VERSION).matcher(manifest);
            if (!m.find()) {
                throw new SdkException(
                        "Minecraft " + MC_VERSION + " is not in Mojang's version manifest");
            }
            download(m.group(1), cached, m.group(2));
        }
        return Files.readString(cached);
    }

    /** The game jar: the installation's (checked by the json's sha1), or downloaded from Mojang. */
    private Path clientJar(String json) throws IOException, InterruptedException, SdkException {
        String client = object(json, "client");
        String sha1 = field(client, "sha1");
        Path local = versionFile("jar");
        if (Files.isRegularFile(local) && sha1.equals(sha1(local))) {
            System.out.println("minecraft: " + local);
            return local;
        }
        Path jar =
                download(
                        field(client, "url"),
                        home.resolve("minecraft-" + MC_VERSION + ".jar"),
                        sha1);
        System.out.println("minecraft: downloaded from Mojang");
        return jar;
    }

    /**
     * The game's libraries for compiling (no natives): the installation's, or downloaded. One
     * version per name.
     */
    private List<Path> libraries(String json)
            throws IOException, InterruptedException, SdkException {
        Map<String, Path> out = new LinkedHashMap<>();
        Matcher a = Pattern.compile("\"artifact\":\\s*\\{([^}]*)\\}").matcher(json);
        while (a.find()) {
            String art = a.group(1);
            String path = field(art, "path");
            String ga = path.substring(0, path.lastIndexOf('/', path.lastIndexOf('/') - 1));
            if (out.containsKey(ga) || path.contains("-natives-")) {
                continue;
            }
            Path local = mc.resolve("libraries").resolve(path);
            out.put(
                    ga,
                    Files.isRegularFile(local)
                            ? local
                            : download(
                                    field(art, "url"),
                                    home.resolve("libraries").resolve(path),
                                    field(art, "sha1")));
        }
        System.out.println("libraries: " + out.size());
        return new ArrayList<>(out.values());
    }

    /**
     * The stein-api.jar (and the sources) of this SDK version in the working folder. Not only in
     * the setup: whoever updates the SDK would compile against the old API until running the setup
     * again, without warning — and so would the IDE, which points to this file. Rewrites only what
     * changed.
     */
    private void installApi() throws IOException, SdkException {
        for (String r : new String[] {"stein-api.jar", "stein-api-sources.jar"}) {
            byte[] now;
            try (InputStream in = Sdk.class.getResourceAsStream("/stein/" + r)) {
                if (in == null) {
                    throw new SdkException("this stein-sdk.jar is missing " + r);
                }
                now = in.readAllBytes();
            }
            Path to = home.resolve(r);
            if (Files.isRegularFile(to) && Arrays.equals(Files.readAllBytes(to), now)) {
                continue;
            }
            boolean update = Files.exists(to);
            Files.write(to, now);
            if (update) {
                System.out.println(r + " updated to the one in this SDK");
            }
        }
    }

    /**
     * The jars for compiling. With a project that asks for "access" in steinmod.json, the game jar
     * is the one opened only for it (in &lt;project&gt;/build/stein), not the shared one — it is
     * what Gradle and the IDE need to see in order to compile the same as the build.
     */
    private List<Path> classpath(Path project) throws IOException, SdkException {
        requireSetup();
        installApi();
        List<Path> cp = new ArrayList<>();
        cp.add(project != null ? compileJar(project) : namedOpen());
        cp.add(home.resolve("stein-api.jar"));
        for (String l : Files.readAllLines(home.resolve("libraries.txt"))) {
            if (!l.isBlank()) {
                cp.add(Paths.get(l));
            }
        }
        return cp;
    }

    /**
     * The game jar for compiling the project: the protected one that the mod asks for in "access"
     * opens only in its own jar. Without "access", the shared one. Rebuilt only when the request
     * (or the game assembled by the setup) changes: the IDE points to this file.
     */
    private Path compileJar(Path project) throws IOException {
        Path manifest = project.resolve("src/main/resources/steinmod.json");
        Set<String> asked = Files.isRegularFile(manifest) ? OpenJar.access(manifest) : Set.of();
        if (asked.isEmpty()) {
            return namedOpen();
        }
        Path work = project.resolve("build/stein");
        Path open = work.resolve("minecraft-compile.jar");
        Path stamp = work.resolve("access.txt");
        String want =
                Files.getLastModifiedTime(named()).toMillis()
                        + "\n"
                        + String.join("\n", new TreeSet<>(asked));
        if (Files.isRegularFile(open)
                && Files.isRegularFile(stamp)
                && Files.readString(stamp).equals(want)) {
            return open;
        }
        Files.createDirectories(work);
        OpenJar.open(named(), open, asked);
        Files.writeString(stamp, want);
        return open;
    }

    // ---- build ----

    /**
     * The steinmod.json of the resources folder, checked; returns the mod file's name
     * (&lt;Name&gt;.steinmod).
     */
    private static String manifest(Path res) throws IOException, SdkException {
        Path manifest = res.resolve("steinmod.json");
        if (!Files.isRegularFile(manifest)) {
            throw new SdkException("no steinmod.json in " + res);
        }
        String json = Files.readString(manifest);
        String id = field(json, "id");
        String name = field(json, "name");
        if (id.isEmpty() || field(json, "entry").isEmpty() || field(json, "loader").isEmpty()) {
            throw new SdkException("steinmod.json needs \"id\", \"entry\" and \"loader\"");
        }
        return (name.isEmpty() ? id : name).replaceAll("[^A-Za-z0-9._-]", "") + ".steinmod";
    }

    private Path build(Path project) throws IOException, SdkException, InterruptedException {
        Path src = project.resolve("src/main/java");
        Path res = project.resolve("src/main/resources");
        if (!Files.isRegularFile(res.resolve("steinmod.json"))) {
            throw new SdkException("no src/main/resources/steinmod.json in " + project);
        }
        String file = manifest(res);
        Path work = project.resolve("build/stein");
        deleteTree(work.resolve("classes"));
        Files.createDirectories(work.resolve("classes"));
        List<Path> cp = classpath(project);
        Path libs = project.resolve("libs");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> s = Files.list(libs)) {
                s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(cp::add);
            }
        }
        compile(src, work.resolve("classes"), cp);

        Path target = project.resolve("build").resolve(file);
        int n = pack(work.resolve("classes"), res, target);
        System.out.println("built " + target + " (" + n + " classes)");
        return target;
    }

    /**
     * Already compiled classes (with readable names) -&gt; the .steinmod: translates to the game's
     * names ({@link Mappings}), checks every reference to the game ({@link ReferenceCheck}) and
     * packages with the resources. It is what the build does after javac, and what the Gradle
     * plugin calls after compileJava. Returns how many classes there were.
     */
    private int pack(Path classesDir, Path res, Path target) throws IOException, SdkException {
        requireSetup();
        manifest(res);
        if (!Files.isDirectory(classesDir)) {
            throw new SdkException("no compiled classes in " + classesDir);
        }

        // Readable names -> the game's.
        Mappings map =
                Mappings.load(
                        home.resolve("mcp/joined.srg"),
                        home.resolve("mcp/fields.csv"),
                        home.resolve("mcp/methods.csv"));
        Mappings.Hierarchy named = new Mappings.Hierarchy();
        named.addJar(named());
        Map<String, byte[]> classes = new TreeMap<>();
        try (Stream<Path> s = Files.walk(classesDir)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".class")).toList()) {
                byte[] b = Files.readAllBytes(p);
                named.add(b);
                classes.put(entryName(classesDir, p), b);
            }
        }
        if (classes.isEmpty()) {
            throw new SdkException("no .class files in " + classesDir);
        }
        var toObf = map.toObf(named);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            out.put(e.getKey(), Mappings.remap(e.getValue(), toObf, named));
        }
        ReferenceCheck.check(
                out, Paths.get(Files.readString(home.resolve("client.txt")).trim()), map);

        List<Path> resources;
        try (Stream<Path> s = Files.walk(res)) {
            resources = s.filter(Files::isRegularFile).sorted().toList();
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> e : out.entrySet()) {
                writeEntry(jar, e.getKey(), e.getValue());
            }
            for (Path p : resources) {
                writeEntry(jar, entryName(res, p), Files.readAllBytes(p));
            }
        }
        return out.size();
    }

    /** Jar entry name of a file under root: relative, with '/'. */
    private static String entryName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void writeEntry(JarOutputStream jar, String name, byte[] data)
            throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(data);
        jar.closeEntry();
    }

    private void compile(Path src, Path out, List<Path> cp) throws IOException, SdkException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new SdkException("no Java compiler: run the SDK with a JDK, not a JRE");
        }
        List<String> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        if (files.isEmpty()) {
            throw new SdkException("no .java files in " + src);
        }
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "--release",
                                String.valueOf(RELEASE),
                                "-proc:none",
                                "-encoding",
                                "UTF-8",
                                "-nowarn",
                                "-d",
                                out.toString(),
                                "-cp",
                                String.join(
                                        File.pathSeparator,
                                        cp.stream().map(Path::toString).toList())));
        args.addAll(files);
        int rc = javac.run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) {
            throw new SdkException("compilation failed");
        }
    }

    private void install(Path mod) throws IOException {
        Path mods = mc.resolve("mods");
        Files.createDirectories(mods);
        Files.copy(mod, mods.resolve(mod.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("installed into " + mods);
    }

    // ---- utilities ----

    /** The object {"...": {...}} named key in the json (without nesting inside it). */
    private static String object(String json, String key) throws SdkException {
        Matcher m =
                Pattern.compile("\"" + Pattern.quote(key) + "\":\\s*\\{([^}]*)\\}").matcher(json);
        if (!m.find()) {
            throw new SdkException("\"" + key + "\" not found");
        }
        return m.group(1);
    }

    /** The text value of key ("" if there is none). */
    static String field(String json, String key) {
        Matcher m =
                Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                        .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static final HttpClient HTTP =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private static byte[] fetch(String url) throws IOException, InterruptedException, SdkException {
        HttpResponse<byte[]> r =
                HTTP.send(
                        HttpRequest.newBuilder(URI.create(url)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new SdkException("download failed (" + r.statusCode() + "): " + url);
        }
        return r.body();
    }

    /** The .sha1 that maven publishes next to the file. */
    private static String sha1Of(String url)
            throws IOException, InterruptedException, SdkException {
        return new String(fetch(url + ".sha1"), StandardCharsets.US_ASCII).trim().split("\\s")[0];
    }

    /**
     * Downloads to target, checked by sha1; if it is already there with the right sha1, does not
     * download.
     */
    private static Path download(String url, Path target, String sha1)
            throws IOException, InterruptedException, SdkException {
        if (Files.isRegularFile(target) && sha1.equals(sha1(target))) {
            return target;
        }
        byte[] data = fetch(url);
        String got = HexFormat.of().formatHex(sha1Digest().digest(data));
        if (!got.equals(sha1)) {
            throw new SdkException("checksum mismatch for " + url);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return target;
    }

    private static String sha1(Path file) throws IOException {
        return HexFormat.of().formatHex(sha1Digest().digest(Files.readAllBytes(file)));
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void unzip(Path zip, String name, Path target) throws IOException, SdkException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e =
                    z.stream()
                            .filter(
                                    x ->
                                            x.getName().equals(name)
                                                    || x.getName().endsWith("/" + name))
                            .findFirst()
                            .orElse(null);
            if (e == null) {
                throw new SdkException(name + " not found in " + zip.getFileName());
            }
            try (InputStream in = z.getInputStream(e)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
