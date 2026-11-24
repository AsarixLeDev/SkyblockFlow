package dev.asarix.flow.update;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Standalone JDK-only worker for a verified update whose active JAR is locked. */
public final class UpdateSwap {
    static String hash(Path path) throws IOException {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path); var stream = new DigestInputStream(in,md)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static void replace(Path pending, Path active, String previous, String next) throws IOException {
        if (!pending.getParent().equals(active.getParent()) || Files.isSymbolicLink(active) ||
            Files.isSymbolicLink(pending) || !hash(pending).equals(next) ||
            !(previous.isEmpty() ? !Files.exists(active) : Files.isRegularFile(active) && hash(active).equals(previous)))
            throw new IOException("The installation changed. Check for updates again.");
        Files.move(pending,active,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws Exception {
        ProcessHandle.of(Long.parseLong(args[0])).ifPresent(p -> p.onExit().join());
        Path pending = Path.of(args[1]), active = Path.of(args[2]);
        for (int i=0;;i++) {
            try { replace(pending,active,args[3],args[4]); return; }
            catch (FileSystemException e) { if (i==30) throw e; TimeUnit.MILLISECONDS.sleep(250); }
        }
    }
}
