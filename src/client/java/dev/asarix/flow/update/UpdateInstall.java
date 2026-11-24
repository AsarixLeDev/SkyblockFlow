package dev.asarix.flow.update;

import com.google.gson.*;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

final class UpdateInstall {
    static final String MOD = "skyblockflow";

    static JsonObject metadata(Path file) throws IOException {
        try (JarFile jar = new JarFile(file.toFile())) {
            var e = jar.getJarEntry("fabric.mod.json");
            if (e == null) throw new IOException("Missing Fabric metadata.");
            try (var in = jar.getInputStream(e)) {
                byte[] data = in.readNBytes(65537);
                if (data.length > 65536) throw new IOException("Invalid Fabric metadata.");
                return JsonParser.parseString(new String(data,StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
    }

    static void verify(Path file, Release release) throws Exception {
        verify(file,release,MOD);
    }
    static void verify(Path file,Release release,String modId) throws Exception {
        if (Files.size(file)!=release.bytes() || !UpdateSwap.hash(file).equals(release.digest()))
            throw new IOException("The download failed its integrity check.");
        var meta = metadata(file);
        if (!modId.equals(meta.get("id").getAsString()) || !release.version().equals(meta.get("version").getAsString()))
            throw new IOException("The JAR identity or version differs from the release.");
        var deps = meta.getAsJsonObject("depends");
        for (String id : List.of("minecraft","fabricloader","java","fabric-api")) {
            var requirements = deps.get(id);
            if (requirements == null) continue;
            var actual = FabricLoader.getInstance().getModContainer(id).orElseThrow().getMetadata().getVersion();
            var predicates = new ArrayList<String>();
            if (requirements.isJsonArray()) requirements.getAsJsonArray().forEach(v -> predicates.add(v.getAsString()));
            else predicates.add(requirements.getAsString());
            boolean compatible = false;
            for (var predicate : predicates) compatible |= VersionPredicate.parse(predicate).test(actual);
            if (!compatible) throw new IOException("This update requires "+id+" "+String.join(" or ",predicates)+".");
        }
    }

    static Path active(String host, Path preferences) throws IOException {
        return active(host,preferences,!MOD.equals(host));
    }
    static Path active(String host,Path preferences,boolean preview) throws IOException {
        if (preview) {
            var test = preferences.resolve("test-instance/mods");
            Files.createDirectories(test);
            return test.resolve("skyblockflow.jar");
        }
        var loader = FabricLoader.getInstance();
        var origin = loader.getModContainer(host).orElseThrow().getOrigin().getPaths();
        var lunar = Path.of(System.getProperty("user.home"),".lunarclient","profiles").toAbsolutePath().normalize();
        var ordinary = loader.getGameDir().resolve("mods").toAbsolutePath().normalize();
        for (Path candidate : origin) {
            var file = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file) || !file.toString().endsWith(".jar")) continue;
            var dir = file.getParent();
            boolean lunarMods = dir.startsWith(lunar) && lunar.relativize(dir).getNameCount()==3 &&
                    dir.getParent().getFileName().toString().equals("mods") && dir.getFileName().toString().startsWith("fabric-");
            if (dir.equals(ordinary) || lunarMods) return file;
            // Some launchers load a cache copy. Resolve only an identical JAR in a recognized mod folder.
            var matches = new ArrayList<Path>();
            var roots = new ArrayList<Path>();
            roots.add(ordinary);
            if (Files.isDirectory(lunar)) try (var profiles = Files.list(lunar)) {
                var game = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                profiles.forEach(p -> roots.add(p.resolve("mods/fabric-"+game)));
            }
            String digest = UpdateSwap.hash(file);
            for (var root : roots) if (Files.isDirectory(root)) try (var files = Files.list(root)) {
                for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    if (Files.isRegularFile(path) && Files.size(path)==Files.size(file) && UpdateSwap.hash(path).equals(digest))
                        matches.add(path);
                }
            }
            if (matches.size()==1) return matches.getFirst();
        }
        throw new IOException("Could not identify the active mod folder. Your installation is unchanged.");
    }

    static boolean install(Path temp, Path active, String oldHash, String digest, Path settings) throws IOException {
        try { UpdateSwap.replace(temp,active,oldHash,digest); return true; }
        catch (AccessDeniedException locked) { /* Windows launchers can retain a JAR handle. */ }
        catch (FileSystemException locked) {
            if (!System.getProperty("os.name","").startsWith("Windows")) throw locked;
        }
        Path helper = Files.createTempDirectory(settings,"installer-");
        String name = UpdateSwap.class.getName().replace('.','/')+".class";
        Path cls = helper.resolve(name);
        Files.createDirectories(cls.getParent());
        try (var source = UpdateSwap.class.getResourceAsStream("/"+name)) { Files.copy(source,cls); }
        Path java = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"javaw.exe":"java");
        new ProcessBuilder(java.toString(),"-cp",helper.toString(),UpdateSwap.class.getName(),
                Long.toString(ProcessHandle.current().pid()),temp.toString(),active.toString(),oldHash,digest)
                .redirectErrorStream(true).redirectOutput(helper.resolve("install.log").toFile()).start();
        return false;
    }
}
