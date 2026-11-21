package dev.asarix.skyblockflow;

import java.util.*;
import java.util.function.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

/** Retains all server data while filtering packets before vanilla or a replacement HUD sees them. */
public final class JobBossVisibility implements ClientboundBossEventPacket.Handler {
  private final Map<UUID, BossEvent> all = new LinkedHashMap<>();
  private final Set<UUID> visible = new HashSet<>();
  private final Predicate<String> hidden;
  private final Consumer<ClientboundBossEventPacket> display;
  private UUID touched;
  private boolean replaying, added, removed;

  public JobBossVisibility(Predicate<String> hidden, Consumer<ClientboundBossEventPacket> display) {
    this.hidden = hidden; this.display = display;
  }
  public boolean replaying() { return replaying; }
  public void reset() { all.clear(); visible.clear(); }
  public boolean consume(ClientboundBossEventPacket packet) {
    touched = null; added = removed = false;
    packet.dispatch(this);
    if (touched == null) return false;
    boolean wasVisible = visible.contains(touched);
    var event = all.get(touched);
    if (removed) { visible.remove(touched); return !wasVisible; }
    if (event == null) return false;
    if (hidden.test(event.getName().getString())) {
      if (visible.remove(touched)) emit(ClientboundBossEventPacket.createRemovePacket(touched));
      return true;
    }
    visible.add(touched);
    if (!wasVisible && !added) {
      emit(ClientboundBossEventPacket.createAddPacket(event));
      return true;
    }
    return false;
  }
  public void sync() {
    for (var entry : all.entrySet()) {
      UUID id = entry.getKey(); var event = entry.getValue();
      if (hidden.test(event.getName().getString())) {
        if (visible.remove(id)) emit(ClientboundBossEventPacket.createRemovePacket(id));
      } else if (visible.add(id)) emit(ClientboundBossEventPacket.createAddPacket(event));
    }
  }
  private void emit(ClientboundBossEventPacket packet) {
    replaying = true;
    try { display.accept(packet); } finally { replaying = false; }
  }
  private void change(UUID id, Consumer<BossEvent> action) {
    touched = id; var event = all.get(id); if (event != null) action.accept(event);
  }
  public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color,
      BossEvent.BossBarOverlay overlay, boolean dark, boolean music, boolean fog) {
    touched = id; added = true;
    var event = new BossEvent(id, name, color, overlay) {};
    event.setProgress(progress); event.setDarkenScreen(dark); event.setPlayBossMusic(music); event.setCreateWorldFog(fog);
    all.put(id, event);
  }
  public void remove(UUID id) { touched = id; removed = true; all.remove(id); }
  public void updateProgress(UUID id, float progress) { change(id, e -> e.setProgress(progress)); }
  public void updateName(UUID id, Component name) { change(id, e -> e.setName(name)); }
  public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
    change(id, e -> { e.setColor(color); e.setOverlay(overlay); });
  }
  public void updateProperties(UUID id, boolean dark, boolean music, boolean fog) {
    change(id, e -> { e.setDarkenScreen(dark); e.setPlayBossMusic(music); e.setCreateWorldFog(fog); });
  }
}
