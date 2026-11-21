package dev.asarix.skyblockflow.mixin;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.Models.Feature;
import dev.asarix.skyblockflow.ui.JobStatsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPacketListener.class)
public abstract class JobCommandMixin {
  @Inject(method="sendCommand", at=@At("HEAD"), cancellable=true)
  private void skyblockflow$jobs(String command, CallbackInfo ci) {
    var app = SkyblockFlowClient.INSTANCE;
    if (app == null || app.closest == null) return;
    if (!app.closest.internalCommand() && command.strip().matches("(?i)/?jobs?\\s+stats")
        && app.store.config.enabled(Feature.JOBS) && !app.store.config.jobs.vanillaJobsStats) {
      app.closest.cancel();
      app.closest.requestPersonalStats();
      var mc = Minecraft.getInstance();
      mc.schedule(() -> mc.gui.setScreen(new JobStatsScreen(app, null)));
      ci.cancel();
      return;
    }
    app.commandSent(command);
  }
}
