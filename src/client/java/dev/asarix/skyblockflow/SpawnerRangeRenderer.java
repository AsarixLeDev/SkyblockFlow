package dev.asarix.skyblockflow;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.asarix.skyblockflow.core.SpawnerSettings;
import dev.asarix.skyblockflow.mixin.SpawnerRenderAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class SpawnerRangeRenderer {
  private static final int UNKNOWN = 0xbb6df6, IN_RANGE = 0x52ef86, OUT_OF_RANGE = 0xf05461;
  private static final float[] BOX = {
    -1, -1, -1, -1, 1, -1, 1, 1, -1, 1, -1, -1,
    1, -1, 1, 1, 1, 1, -1, 1, 1, -1, -1, 1,
    -1, -1, 1, -1, 1, 1, -1, 1, -1, -1, -1, -1,
    1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1,
    -1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1,
    -1, -1, 1, -1, -1, -1, 1, -1, -1, 1, -1, 1
  };
  private static final float[] GLOW_OUTLINE = outline(.06f);
  private static final float[] LIGHT_OUTLINE = outline(.009f);
  private static final RenderType GLOW =
      SpawnerRenderAccess.skyblockflow$create(
          "skyblockflow_spawner_glow",
          RenderSetup.builder(
                  RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                      .withLocation(
                          Identifier.fromNamespaceAndPath("skyblockflow", "pipeline/spawner_glow"))
                      .withDepthStencilState(Optional.empty())
                      .build())
              .createRenderSetup());
  private static final float[] NEAR_SPHERE = sphere(20, 10);
  private static final float[] FAR_SPHERE = sphere(12, 6);

  private record Shape(Vec3 center, float radius, int color, float[] mesh) {}

  private record Frame(List<Shape> depthTested, List<Shape> glow) {}

  private static final Frame EMPTY = new Frame(List.of(), List.of());
  private volatile Frame frame = EMPTY;

  void markers(List<Vec3> positions, int rgb) { markers(positions, rgb, false); }

  void markers(List<Vec3> positions, int rgb, boolean throughBlocks) {
    var shapes = new ArrayList<Shape>();
    for (var pos : positions.stream().limit(256).toList())
      shapes.add(new Shape(pos, .515f, 0xa0000000 | rgb, GLOW_OUTLINE));
    frame = throughBlocks ? new Frame(List.of(), List.copyOf(shapes)) : new Frame(List.copyOf(shapes), List.of());
  }

  void clear() {
    frame = EMPTY;
  }

  void prepare(
      List<SpawnerRanges.Spawner> spawners,
      Vec3 player,
      Frustum frustum,
      SpawnerSettings options,
      int maxHighlights,
      int maxSpheres) {
    boolean highlights = options.highlight;
    int alpha = Math.round(options.sphereOpacity * 255 / 100f);
    boolean spheres = options.spheres && alpha > 0;
    if (!highlights && !spheres && !options.warnings) {
      clear();
      return;
    }
    var shapes = new ArrayList<Shape>();
    var glow = new ArrayList<Shape>();
    int lightBudget = 512;
    var highlightedLight = new java.util.HashSet<Vec3>();
    for (var spawner : spawners) {
      var radius = spawner.range();
      double distanceSquared = spawner.center().distanceToSqr(player);
      boolean inRange = radius != null && distanceSquared < (double) radius * radius;
      int rgb = radius == null ? UNKNOWN : inRange ? IN_RANGE : OUT_OF_RANGE;
      boolean warning = options.warnings && !spawner.warning().isEmpty();
      if (warning) rgb = 0xffa840;
      if (warning)
        for (var lit : spawner.lit()) {
          if (lightBudget <= 0) break;
          if (highlightedLight.add(lit) && visible(frustum, lit, .51)) {
            shapes.add(new Shape(lit, .51f, 0x30ffa840, LIGHT_OUTLINE));
            lightBudget--;
          }
        }
      if ((highlights || warning)
          && maxHighlights > 0
          && visible(frustum, spawner.center(), .515)) {
        shapes.add(new Shape(spawner.center(), .515f, 0x80000000 | rgb, BOX));
        if (options.glowThroughBlocks) {
          glow.add(new Shape(spawner.center(), .535f, 0xde000000 | rgb, GLOW_OUTLINE));
        }
        maxHighlights--;
      }
      if (spheres
          && radius != null
          && (!options.spheresOutOfRangeOnly || !inRange)
          && maxSpheres > 0
          && visible(frustum, spawner.center(), radius)) {
        var mesh = distanceSquared < 28 * 28 ? NEAR_SPHERE : FAR_SPHERE;
        shapes.add(new Shape(spawner.center(), radius, (alpha << 24) | rgb, mesh));
        maxSpheres--;
      }
      if ((!highlights && !options.warnings || maxHighlights == 0) && (!spheres || maxSpheres == 0))
        break;
    }
    frame = new Frame(List.copyOf(shapes), List.copyOf(glow));
  }

  private static boolean visible(Frustum frustum, Vec3 center, double radius) {
    return frustum == null
        || frustum.isVisible(
            new AABB(
                center.x - radius,
                center.y - radius,
                center.z - radius,
                center.x + radius,
                center.y + radius,
                center.z + radius));
  }

  void submit(SubmitNodeCollector collector, PoseStack pose, Vec3 camera) {
    var current = frame;
    if (!current.depthTested().isEmpty()) {
      collector.submitCustomGeometry(
          pose, RenderTypes.debugQuads(), geometry(current.depthTested(), camera));
    }
    if (!current.glow().isEmpty()) {
      collector.order(1).submitCustomGeometry(pose, GLOW, geometry(current.glow(), camera));
    }
  }

  private static SubmitNodeCollector.CustomGeometryRenderer geometry(
      List<Shape> shapes, Vec3 camera) {
    return (transform, buffer) -> {
      for (var shape : shapes) {
        float x = (float) (shape.center().x - camera.x);
        float y = (float) (shape.center().y - camera.y);
        float z = (float) (shape.center().z - camera.z);
        var mesh = shape.mesh();
        for (int v = 0; v < mesh.length; v += 3) {
          vertex(buffer, transform, shape, mesh, v, x, y, z);
        }
      }
    };
  }

  private static void vertex(
      VertexConsumer buffer,
      PoseStack.Pose transform,
      Shape shape,
      float[] mesh,
      int v,
      float x,
      float y,
      float z) {
    buffer
        .addVertex(
            transform,
            x + mesh[v] * shape.radius(),
            y + mesh[v + 1] * shape.radius(),
            z + mesh[v + 2] * shape.radius())
        .setColor(shape.color());
  }

  // Four strips on each face make the outline visible from any viewing angle.
  private static float[] outline(float thickness) {
    float[] mesh = new float[BOX.length * 4];
    int index = 0;
    for (int face = 0; face < BOX.length; face += 12) {
      for (int edge = 0; edge < 4; edge++) {
        int a = face + edge * 3;
        int b = face + ((edge + 1) % 4) * 3;
        int c = face + ((edge + 2) % 4) * 3;
        int d = face + ((edge + 3) % 4) * 3;
        System.arraycopy(BOX, a, mesh, index, 3);
        System.arraycopy(BOX, b, mesh, index + 3, 3);
        for (int axis = 0; axis < 3; axis++) {
          mesh[index + 6 + axis] = BOX[b + axis] + thickness * (BOX[c + axis] - BOX[b + axis]);
          mesh[index + 9 + axis] = BOX[a + axis] + thickness * (BOX[d + axis] - BOX[a + axis]);
        }
        index += 12;
      }
    }
    return mesh;
  }

  private static float[] sphere(int longitudes, int latitudes) {
    float[] mesh = new float[longitudes * latitudes * 12];
    int index = 0;
    for (int row = 0; row < latitudes; row++) {
      for (int col = 0; col < longitudes; col++) {
        point(mesh, index, row, col, latitudes, longitudes);
        point(mesh, index + 3, row + 1, col, latitudes, longitudes);
        point(mesh, index + 6, row + 1, col + 1, latitudes, longitudes);
        point(mesh, index + 9, row, col + 1, latitudes, longitudes);
        index += 12;
      }
    }
    return mesh;
  }

  private static void point(
      float[] mesh, int index, int row, int col, int latitudes, int longitudes) {
    double latitude = Math.PI * ((double) row / latitudes - .5);
    double longitude = 2 * Math.PI * col / longitudes;
    double horizontal = Math.cos(latitude);
    mesh[index] = (float) (horizontal * Math.cos(longitude));
    mesh[index + 1] = (float) Math.sin(latitude);
    mesh[index + 2] = (float) (horizontal * Math.sin(longitude));
  }
}
