package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.ExcaliaApiData.Market;
import dev.asarix.skyblockflow.core.MarketSign;
import dev.asarix.skyblockflow.core.MarketStock;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;

/** Reads signs already sent by the server; does not load chunks or interact with blocks. */
final class MarketSignObserver {
  private static final int RADIUS = 16;
  private record Face(long position, boolean front) {}
  private record Seen(List<String> lines, MarketSign empty, long at) {}
  private final LinkedHashMap<Face, Seen> seen = new LinkedHashMap<>();
  private Object level;
  private int firstChunk;

  void reset() { seen.clear(); level = null; firstChunk = 0; }

  void tick(Minecraft mc, Market market, MarketStock stock, long now) {
    if (level != mc.level) { reset(); level = mc.level; }
    if (mc.level == null || mc.player == null) return;
    var center = mc.player.blockPosition();
    if (mc.hitResult instanceof BlockHitResult hit
        && hit.getBlockPos().distSqr(center) <= RADIUS * RADIUS
        && mc.level.getBlockEntity(hit.getBlockPos()) instanceof SignBlockEntity sign)
      observe(sign, market, stock, now);
    // Nine loaded chunks cover the whole radius, including signs next to the crosshair.
    int remaining = 4096;
    for (int step = 0; step < 9 && remaining > 0; step++) {
      int offset = (firstChunk + step) % 9;
      var chunk = mc.level.getChunkSource().getChunk((center.getX() >> 4) + offset % 3 - 1,
          (center.getZ() >> 4) + offset / 3 - 1, ChunkStatus.FULL, false);
      if (chunk == null) continue;
      for (var entity : chunk.getBlockEntities().values()) {
        if (--remaining < 0) break;
        if (entity instanceof SignBlockEntity sign && sign.getBlockPos().distSqr(center) <= RADIUS * RADIUS)
          observe(sign, market, stock, now);
      }
    }
    firstChunk = (firstChunk + 1) % 9;
  }

  private void observe(SignBlockEntity sign, Market market, MarketStock stock, long now) {
    read(sign, true, market, stock, now);
    read(sign, false, market, stock, now);
  }

  private void read(SignBlockEntity sign, boolean front, Market market, MarketStock stock, long now) {
    List<String> lines = Arrays.stream(sign.getText(front).getMessages(false)).map(Component::getString).toList();
    var face = new Face(sign.getBlockPos().asLong(), front);
    Seen observation = seen.get(face);
    if (observation == null || !observation.lines.equals(lines)) {
      observation = new Seen(lines, MarketSign.parse(lines), now);
      if (seen.size() >= 1024 && !seen.containsKey(face)) seen.remove(seen.firstEntry().getKey());
      seen.put(face, observation);
    }
    // A newer API snapshot may describe a restock. Re-reading unchanged text must not
    // turn an older sign observation into a fresh confirmation of zero stock.
    if (market == null || observation.empty == null || market.at() > observation.at) return;
    var empty = observation.empty;
    var offer = stock.identify(market, empty.item(), empty.price(), empty.owner(), "");
    if (offer != null) stock.remaining(market, offer, 0, observation.at);
  }
}
