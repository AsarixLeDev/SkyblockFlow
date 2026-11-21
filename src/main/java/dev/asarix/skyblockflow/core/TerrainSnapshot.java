package dev.asarix.skyblockflow.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Palette + run-length terrain. All dimensions and allocations are bounded before decoding. */
public final class TerrainSnapshot {
  public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_CELLS = 97 * 65 * 97;
  public final int x, y, z, width, height, depth;
  public final List<String> palette;
  public final int[] blocks, grass, foliage, water;
  public final byte[] light;

  public TerrainSnapshot(
      int x,
      int y,
      int z,
      int width,
      int height,
      int depth,
      List<String> palette,
      int[] blocks,
      byte[] light,
      int[] grass,
      int[] foliage,
      int[] water) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.width = width;
    this.height = height;
    this.depth = depth;
    this.palette = List.copyOf(palette);
    this.blocks = blocks;
    this.light = light;
    this.grass = grass;
    this.foliage = foliage;
    this.water = water;
  }

  public int index(int px, int py, int pz) {
    int dx = px - x, dy = py - y, dz = pz - z;
    return dx < 0 || dx >= width || dy < 0 || dy >= height || dz < 0 || dz >= depth
        ? -1
        : (dy * depth + dz) * width + dx;
  }

  /** Keep a smaller area around the subject without changing the received snapshot. */
  public TerrainSnapshot crop(int cx, int cy, int cz, int radius) {
    if (radius < 1 || radius > 48 || index(cx, cy, cz) < 0)
      throw new IllegalArgumentException("Invalid terrain crop");
    int left = Math.max(x, cx - radius),
        bottom = Math.max(y, cy - radius),
        front = Math.max(z, cz - radius);
    int w = Math.min(x + width, cx + radius + 1) - left,
        h = Math.min(y + height, cy + radius + 1) - bottom,
        d = Math.min(z + depth, cz + radius + 1) - front;
    int[] croppedBlocks = new int[w * h * d],
        croppedGrass = new int[w * d],
        croppedFoliage = new int[w * d],
        croppedWater = new int[w * d];
    byte[] croppedLight = new byte[croppedBlocks.length];
    for (int dy = 0; dy < h; dy++)
      for (int dz = 0; dz < d; dz++) {
        int from = index(left, bottom + dy, front + dz), to = (dy * d + dz) * w;
        System.arraycopy(blocks, from, croppedBlocks, to, w);
        System.arraycopy(light, from, croppedLight, to, w);
      }
    for (int dz = 0; dz < d; dz++) {
      int from = (front + dz - z) * width + left - x, to = dz * w;
      System.arraycopy(grass, from, croppedGrass, to, w);
      System.arraycopy(foliage, from, croppedFoliage, to, w);
      System.arraycopy(water, from, croppedWater, to, w);
    }
    return new TerrainSnapshot(
        left,
        bottom,
        front,
        w,
        h,
        d,
        palette,
        croppedBlocks,
        croppedLight,
        croppedGrass,
        croppedFoliage,
        croppedWater);
  }

  private static int size(int x, int y, int z, int w, int h, int d) throws IOException {
    if (Math.abs((long) x) > 30000000
        || y < -4096
        || y > 4096
        || Math.abs((long) z) > 30000000
        || w < 1
        || w > 97
        || h < 1
        || h > 65
        || d < 1
        || d > 97) throw new IOException("Terrain snapshot is outside its limits");
    return w * h * d;
  }

  private void validate() throws IOException {
    int cells = size(x, y, z, width, height, depth);
    if (palette.isEmpty()
        || palette.size() > 4096
        || blocks.length != cells
        || light.length != cells
        || grass.length != width * depth
        || foliage.length != width * depth
        || water.length != width * depth) throw new IOException("Incomplete terrain snapshot");
    for (String state : palette)
      if (state == null
          || state.length() > 512
          || !state.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+\\])?"))
        throw new IOException("Invalid terrain block state");
    for (int b : blocks)
      if (b < 0 || b >= palette.size()) throw new IOException("Invalid terrain palette index");
  }

  public byte[] encode() throws IOException {
    validate();
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
      out.writeInt(0x464c5331);
      out.writeInt(x);
      out.writeInt(y);
      out.writeInt(z);
      out.writeInt(width);
      out.writeInt(height);
      out.writeInt(depth);
      out.writeInt(palette.size());
      for (String state : palette) out.writeUTF(state);
      for (int i = 0; i < blocks.length; ) {
        int end = i + 1;
        while (end < blocks.length && blocks[end] == blocks[i] && light[end] == light[i]) end++;
        out.writeInt(end - i);
        out.writeShort(blocks[i]);
        out.writeByte(light[i]);
        i = end;
      }
      for (int i = 0; i < grass.length; i++) {
        out.writeInt(grass[i]);
        out.writeInt(foliage[i]);
        out.writeInt(water[i]);
      }
    }
    if (bytes.size() > MAX_BYTES) throw new IOException("Terrain snapshot exceeds 8 MB");
    return bytes.toByteArray();
  }

  public static TerrainSnapshot decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Terrain snapshot exceeds 8 MB");
    try (var in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
      if (in.readInt() != 0x464c5331) throw new IOException("Unknown terrain snapshot version");
      int x = in.readInt(),
          y = in.readInt(),
          z = in.readInt(),
          w = in.readInt(),
          h = in.readInt(),
          d = in.readInt();
      int cells = size(x, y, z, w, h, d), count = in.readInt();
      if (count < 1 || count > 4096) throw new IOException("Invalid terrain palette size");
      var palette = new ArrayList<String>();
      for (int i = 0; i < count; i++) {
        String state = in.readUTF();
        if (state.length() > 512) throw new IOException("Invalid terrain state");
        palette.add(state);
      }
      int[] blocks = new int[cells];
      byte[] light = new byte[cells];
      for (int i = 0; i < cells; ) {
        int run = in.readInt(), block = in.readUnsignedShort();
        byte value = in.readByte();
        if (run < 1 || run > cells - i || block >= count)
          throw new IOException("Invalid terrain run");
        Arrays.fill(blocks, i, i + run, block);
        Arrays.fill(light, i, i + run, value);
        i += run;
      }
      int[] grass = new int[w * d], foliage = new int[w * d], water = new int[w * d];
      for (int i = 0; i < grass.length; i++) {
        grass[i] = in.readInt();
        foliage[i] = in.readInt();
        water[i] = in.readInt();
      }
      if (in.read() != -1) throw new IOException("Unexpected terrain data");
      var snapshot =
          new TerrainSnapshot(x, y, z, w, h, d, palette, blocks, light, grass, foliage, water);
      snapshot.validate();
      return snapshot;
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid terrain snapshot", invalid);
    }
  }
}
