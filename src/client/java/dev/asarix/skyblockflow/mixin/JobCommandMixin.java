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
    if (!app.closest.internalCommand() && app.excaliaApi != null && app.excaliaApi.connected()) {
      var parts = command.strip().replaceFirst("^/","").split("\\s+");
      var mc = Minecraft.getInstance();
      boolean stats = parts.length >= 2 && parts.length <= 3 && parts[0].matches("(?i)jobs?")
          && parts[1].equalsIgnoreCase("stats") && app.store.config.enabled(Feature.JOBS)
          && !app.store.config.jobs.vanillaJobsStats;
      boolean profile = parts.length <= 2 && parts[0].equalsIgnoreCase("pv");
      if (stats || profile) {
        int argument = stats ? 2 : 1;
        String target = parts.length > argument ? parts[argument] : mc.getUser().getName();
        if (target.matches("[A-Za-z0-9_]{1,16}")) {
          mc.schedule(() -> mc.gui.setScreen(stats ? new JobStatsScreen(app,null,target)
              : new dev.asarix.skyblockflow.ui.PlayerViewScreen(app,null,target)));
          ci.cancel(); return;
        }
      }
    }
    if(app.excaliaApi!=null){
      if(app.excaliaApi.inspections.sendingInspection())return;
      app.excaliaApi.inspections.commandSent(command);
    }
    app.commandSent(command);
  }
}
