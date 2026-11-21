package dev.asarix.skyblockflow.mixin;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import java.util.*;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Only filters the render iteration; packet handling and stored boss data remain intact. */
@Mixin(BossHealthOverlay.class)
public abstract class JobBossVisibilityMixin {
  @Redirect(method="extractRenderState", at=@At(value="INVOKE", target="Ljava/util/Map;values()Ljava/util/Collection;"))
  private Collection<LerpingBossEvent> skyblockflow$visibleBars(Map<UUID, LerpingBossEvent> events) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.jobs == null) return events.values();
    return events.values().stream().filter(e -> !app.jobs.hideBoss(e.getName().getString())).toList();
  }
}
