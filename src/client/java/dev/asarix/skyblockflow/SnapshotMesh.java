package dev.asarix.skyblockflow;

import com.mojang.blaze3d.vertex.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;

/** Meshes are prepared incrementally once; playback only submits their immutable quads. */
public final class SnapshotMesh {
  static final int MAX_VERTICES = 600000;

  public static final class TooDetailedException extends IllegalArgumentException {
    TooDetailedException() {
      super(
          "This scenery could not be prepared. Turn off Share terrain snapshot in /edits and try"
              + " again nearby.");
    }
  }

  private record Quad(float x, float y, float z, BakedQuad model, QuadInstance colors) {}

  private record Vertex(
      float x,
      float y,
      float z,
      int color,
      float u,
      float v,
      int overlay,
      int light,
      float nx,
      float ny,
      float nz) {}

  private final Map<RenderType, List<Quad>> quads = new LinkedHashMap<>();
  private final Map<RenderType, List<Vertex>> fluids = new LinkedHashMap<>();
  public final SnapshotWorld world;
  private final ModelBlockRenderer renderer;
  private final FluidRenderer fluidRenderer;
  private int cursor, vertices;

  public SnapshotMesh(SnapshotWorld world) {
    this.world = world;
    var mc = Minecraft.getInstance();
    renderer = new ModelBlockRenderer(true, true, mc.getBlockColors());
    fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
  }

  public int completed() {
    return cursor;
  }

  public int total() {
    return world.data.blocks.length;
  }

  public boolean step(long deadline) {
    var d = world.data;
    var mc = Minecraft.getInstance();
    var pos = new BlockPos.MutableBlockPos();
    for (; cursor < d.blocks.length && System.nanoTime() < deadline; cursor++) {
      int x = cursor % d.width, z = cursor / d.width % d.depth, y = cursor / (d.width * d.depth);
      pos.set(d.x + x, d.y + y, d.z + z);
      var state = world.getBlockState(pos);
      if (state.getRenderShape() == RenderShape.MODEL)
        renderer.tesselateBlock(
            (px, py, pz, q, colors) -> {
              reserveVertices(4);
              var copy = new QuadInstance();
              for (int v = 0; v < 4; v++) {
                copy.setColor(v, colors.getColor(v));
                copy.setLightCoords(v, colors.getLightCoords(v));
              }
              copy.setOverlayCoords(colors.overlayCoords());
              quads
                  .computeIfAbsent(q.materialInfo().itemRenderType(), k -> new ArrayList<>())
                  .add(new Quad(px, py, pz, q, copy));
            },
            x,
            y,
            z,
            world,
            pos,
            state,
            mc.getModelManager().getBlockStateModelSet().get(state),
            state.getSeed(pos));
      var fluid = state.getFluidState();
      if (!fluid.isEmpty()) {
        var builders = new ArrayList<FluidBuilder>();
        fluidRenderer.tesselate(
            world,
            pos,
            layer -> {
              var type =
                  layer.translucent()
                      ? Sheets.translucentBlockItemSheet()
                      : Sheets.cutoutBlockItemSheet();
              var b =
                  new FluidBuilder(
                      fluids.computeIfAbsent(type, k -> new ArrayList<>()),
                      x - (pos.getX() & 15),
                      y - (pos.getY() & 15),
                      z - (pos.getZ() & 15));
              builders.add(b);
              return b;
            },
            state,
            fluid);
        builders.forEach(FluidBuilder::finish);
      }
    }
    return cursor >= d.blocks.length;
  }

  public int vertices() {
    return vertices;
  }

  private void reserveVertices(int count) {
    if (vertices > MAX_VERTICES - count) throw new TooDetailedException();
    vertices += count;
  }

  public void submit(SubmitNodeCollector out, PoseStack pose) {
    quads.forEach(
        (type, list) ->
            out.submitCustomGeometry(
                pose,
                type,
                (transform, buffer) -> {
                  for (var q : list) {
                    var normal = q.model.direction().getUnitVec3f();
                    for (int v = 0; v < 4; v++) {
                      var p = q.model.position(v);
                      long uv = q.model.packedUV(v);
                      buffer
                          .addVertex(transform, p.x() + q.x, p.y() + q.y, p.z() + q.z)
                          .setColor(q.colors.getColor(v))
                          .setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
                          .setOverlay(q.colors.overlayCoords())
                          .setLight(
                              q.colors.getLightCoordsWithEmission(
                                  v, q.model.materialInfo().lightEmission()))
                          .setNormal(transform, normal.x(), normal.y(), normal.z());
                    }
                  }
                }));
    fluids.forEach(
        (type, list) ->
            out.submitCustomGeometry(
                pose,
                type,
                (transform, buffer) -> {
                  for (var v : list)
                    buffer
                        .addVertex(transform, v.x, v.y, v.z)
                        .setColor(v.color)
                        .setUv(v.u, v.v)
                        .setOverlay(v.overlay)
                        .setLight(v.light)
                        .setNormal(transform, v.nx, v.ny, v.nz);
                }));
  }

  private final class FluidBuilder implements VertexConsumer {
    private final List<Vertex> target;
    private final float dx, dy, dz;
    private float x, y, z, u, v, nx, ny, nz;
    private int color = -1, overlay, light;
    private boolean pending;

    FluidBuilder(List<Vertex> target, float dx, float dy, float dz) {
      this.target = target;
      this.dx = dx;
      this.dy = dy;
      this.dz = dz;
    }

    void finish() {
      if (pending) {
        reserveVertices(1);
        target.add(new Vertex(x + dx, y + dy, z + dz, color, u, v, overlay, light, nx, ny, nz));
        pending = false;
      }
    }

    public VertexConsumer addVertex(float x, float y, float z) {
      finish();
      this.x = x;
      this.y = y;
      this.z = z;
      pending = true;
      return this;
    }

    public VertexConsumer setColor(int r, int g, int b, int a) {
      color = a << 24 | r << 16 | g << 8 | b;
      return this;
    }

    public VertexConsumer setColor(int color) {
      this.color = color;
      return this;
    }

    public VertexConsumer setLineWidth(float width) {
      return this;
    }

    public VertexConsumer setUv(float u, float v) {
      this.u = u;
      this.v = v;
      return this;
    }

    public VertexConsumer setUv1(int u, int v) {
      overlay = u | v << 16;
      return this;
    }

    public VertexConsumer setUv2(int u, int v) {
      light = u | v << 16;
      return this;
    }

    public VertexConsumer setNormal(float x, float y, float z) {
      nx = x;
      ny = y;
      nz = z;
      return this;
    }
  }
}
