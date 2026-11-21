package dev.asarix.skyblockflow.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks allocation bounds before an image decoder sees untrusted pixels. */
public final class PngSafety {
  private PngSafety() {}
  public static void validate(Path path) throws IOException {
    if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("PNG must be smaller than 8 MB");
    try (var input = Files.newInputStream(path)) { validate(input.readNBytes(33)); }
  }
  public static void validate(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length < 33 || bytes.length > 8 * 1024 * 1024)
      throw new IOException("Invalid PNG size");
    var header = ByteBuffer.wrap(bytes);
    if (header.getLong() != 0x89504e470d0a1a0aL || header.getInt() != 13 || header.getInt() != 0x49484452)
      throw new IOException("Expected a PNG image");
    int width = header.getInt(), height = header.getInt();
    if (width < 1 || height < 1 || width > 2048 || height > 2048)
      throw new IOException("Image must fit within 2048 × 2048 pixels");
  }
}
