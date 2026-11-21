package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.SharedEdits;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

public final class SharedEditsScreen extends FormScreen {
  private final SharedEdits.Settings draft;
  private String state = "", lastStatus = "";
  private String presetId = "", presetName = "Current celebration";

  public SharedEditsScreen(SkyblockFlowClient app, Screen parent) {
    super(app, parent, "Shared edits");
    draft = ConfigStore.copy(app.shared.settings, SharedEdits.Settings.class);
    selectPage("Watch & send");
  }

  @Override
  protected String saveLabel() {
    return "Save settings";
  }

  @Override
  protected String description() {
    return "Watch a full edit here, or invite a friend. Choose a preset and a subject.";
  }

  @Override
  protected void buildForm() {
    if (!presetId.isEmpty()) {
      try {
        presetName = new EditPresetStore(app.store.directory()).get(presetId).name();
      } catch (Exception e) {
        presetName = "Unavailable preset";
        notice = e.getMessage();
      }
    }
    page("Connection");
    field("url", "Relay HTTPS address", draft.relayUrl, 0);
    toggle(
        "Enable sharing",
        draft.enabled,
        58,
        () -> {
          if (draft.enabled) { draft.enabled = false; rebuildWidgets(); }
          else {
            String relay = value("url").strip();
            EditRelayClient.address(relay);
            minecraft.gui.setScreen(new SharedEditTrustScreen(app, this, relay, () -> {
              draft.relayUrl = relay; draft.acceptedRelay = relay; draft.enabled = true;
              app.shared.configure(ConfigStore.copy(draft, SharedEdits.Settings.class));
            }));
          }
        });
    toggle(
        "Block all incoming edits",
        draft.blockAll,
        94,
        () -> {
          draft.blockAll = !draft.blockAll;
          rebuildWidgets();
        });
    field("volume", "Incoming volume limit (0–1)", Float.toString(draft.volumeLimit), 140);
    label("Each invitation needs your acceptance before downloading or playing.", 0, 196);
    label("Esc stops playback. /stopedit or /stopedits also clears uploads.", 0, 220);
    label("Both players need sharing enabled and the same relay.", 0, 244);
    label("Songs: MP3 up to 32 MB. Images: PNG up to 2048 × 2048.", 0, 268);
    toggle(
        "Share terrain snapshot (works from far away)",
        draft.shareTerrain,
        302,
        () -> draft.shareTerrain = !draft.shareTerrain);
    field("radius", "Terrain radius (blocks, 8–48)", Integer.toString(draft.terrainRadius), 340);
    label("Frozen loaded terrain; each viewer uses their own textures.", 0, 386);
    label("Images and terrain are removed after playback or cancellation.", 0, 408);
    action("How sharing works & privacy", 0, 474, bodyW, () -> minecraft.gui.setScreen(new SharedEditTrustScreen(app, this, value("url").strip(), null)));
    label(app.shared.cooldownSeconds() > 0 ? "Please wait " + app.shared.cooldownSeconds() + " seconds before sharing again" : "Five minutes between completed shares. Failed shares can be retried.", 0, 516);
    action("Stop edits & clear uploads", 0, 438, bodyW, () -> app.shared.stopAll());
    page("Song storage");
    toggle("Keep downloaded songs", draft.keepDownloadedSongs, 0, () -> {
      draft.keepDownloadedSongs = !draft.keepDownloadedSongs;
      rebuildWidgets();
    });
    int storageY = 48;
    storageY = DiscordGuideScreen.paragraph(this, "Off by default. When off, received songs are removed after the share. Sharing the same edit again is slower because the song must be downloaded again.", storageY, bodyW) + 20;
    storageY = DiscordGuideScreen.paragraph(this, "When on, verified songs are kept on this computer for faster repeat sharing, up to 512 MB. Older songs are removed when the limit is reached. Images and terrain stay temporary.", storageY, bodyW) + 20;
    storageY = DiscordGuideScreen.paragraph(this, "Turning this off also deletes previously saved downloads when the settings are saved. Your own imported music is never deleted.", storageY, bodyW) + 20;
    action("Clear saved songs", 0, storageY, bodyW, () -> {
      try { app.shared.clearSavedSongs(); notice = "Saved downloads cleared. Your imported songs are kept."; }
      catch (Exception e) { notice = e.getMessage(); }
    });
    page("Invitation");
    var i = app.shared.invitation;
    if (i == null) label("No active invitation. New ones appear here.", 0, 0);
    else {
      label(i.senderName + " → " + i.receiverName + " · " + i.entityName, 0, 0);
      label(
          "Status: "
              + i.state
              + (i.remote != null
                  ? " · terrain snapshot · any distance"
                  : i.scene == null
                      ? ""
                      : " · staged avatar at "
                          + Math.round(i.scene.x())
                          + ", "
                          + Math.round(i.scene.y())
                          + ", "
                          + Math.round(i.scene.z())),
          0,
          26);
      action(
          "Accept & watch",
          0,
          58,
          bodyW,
          () -> {
            app.shared.accept();
          });
      action(
          "Decline / cancel",
          0,
          92,
          bodyW,
          () -> {
            app.shared.cancel();
          });
      action(
          "Block sender",
          0,
          126,
          bodyW,
          () -> {
            app.shared.blockSender();
            draft.blocked.putAll(app.shared.settings.blocked);
            rebuildWidgets();
          });
    }
    page("Watch & send");
    action("Edit · " + presetName, 0, 0, bodyW, this::choosePreset);
    action("Watch myself here", 0, 38, bodyW, () -> watchPreset(false, null));
    action("Watch the entity I am looking at", 0, 72, bodyW, () -> watchPreset(true, null));
    action(
        "Stage an offline player...",
        0,
        106,
        bodyW,
        () -> {
          selectPage("Avatar");
          rebuildWidgets();
        });
    field("player", "Friend to invite (your own name plays locally)", "", 154);
    action("Send an edit of myself", 0, 210, bodyW, () -> sendPreset(false));
    action("Send an edit of the entity I am looking at", 0, 244, bodyW, () -> sendPreset(true));
    action(
        presetId.isEmpty() ? "Edit current celebration" : "Edit selected preset",
        0,
        284,
        bodyW,
        () -> {
          try {
            minecraft.gui.setScreen(
                presetId.isEmpty()
                    ? new CelebrationSettingsScreen(app, this, app.store.config.jobs.celebration)
                    : CelebrationSettingsScreen.preset(
                        app, this, new EditPresetStore(app.store.directory()).get(presetId)));
          } catch (Exception e) {
            notice = e.getMessage();
          }
        });
    label("Local playback works with sharing off. /playedit · /playedit target", 0, 328);
    label(
        draft.shareTerrain
            ? "Terrain snapshots let friends watch from any distance."
            : "Nearby mode: both viewers must have the subject loaded.",
        0,
        352);
    page("Avatar");
    var actor = minecraft.player;
    var saved = draft.avatar;
    field(
        "avatarName",
        "Minecraft player name (online or offline)",
        saved != null ? saved.playerName() : actor == null ? "" : actor.getGameProfile().name(),
        0);
    int third = (bodyW - 16) / 3, half = (bodyW - 8) / 2;
    field(
        "avatarX",
        "X",
        Double.toString(saved != null ? saved.x() : actor == null ? 0 : actor.getX() + 3),
        0,
        56,
        third);
    field(
        "avatarY",
        "Y · feet",
        Double.toString(saved != null ? saved.y() : actor == null ? 64 : actor.getY()),
        third + 8,
        56,
        third);
    field(
        "avatarZ",
        "Z",
        Double.toString(saved != null ? saved.z() : actor == null ? 0 : actor.getZ()),
        2 * (third + 8),
        56,
        third);
    action("Place at my position", 0, 112, bodyW, this::placeHere);
    label("Capture a position, then step away to leave room for the avatar.", 0, 148);
    field(
        "avatarYaw",
        "Body yaw (degrees, ±360)",
        Float.toString(saved == null ? 0 : saved.yaw()),
        0,
        182,
        half);
    field(
        "avatarPitch",
        "Pitch center (degrees, ±60)",
        Float.toString(saved == null ? 0 : saved.pitch()),
        half + 8,
        182,
        half);
    field(
        "yawSpeed",
        "Yaw speed (degrees/sec, 0–180)",
        Float.toString(saved == null ? 12 : saved.yawSpeed()),
        0,
        238,
        half);
    field(
        "pitchSpeed",
        "Pitch speed (degrees/sec, 0–90)",
        Float.toString(saved == null ? 5 : saved.pitchSpeed()),
        half + 8,
        238,
        half);
    label("Average speeds for smooth head motion. Zero holds the angle.", 0, 296);
    label("Current edit: " + presetName, 0, 322);
    action("Choose edit preset", 0, 352, bodyW, this::choosePreset);
    action("Watch staged avatar here", 0, 386, bodyW, () -> watchPreset(false, scene()));
    action(
        "Invite friend to staged avatar",
        0,
        420,
        bodyW,
        () -> {
          try {
            app.shared.send(value("player").strip(), false, selectedSettings(), scene());
          } catch (Exception e) {
            notice = e.getMessage();
          }
        });
    label("Uses the friend name from Watch & send. Both need this update.", 0, 464);
    label("Temporary avatar, visible only to participating viewers.", 0, 488);
    page("Blocked players");
    field("blockPlayer", "Block by online player name or UUID", "", 0);
    action(
        "Add blocked player",
        0,
        58,
        bodyW,
        () -> {
          try {
            String input = value("blockPlayer").strip(), id = input, name = input;
            var connection = minecraft.getConnection();
            var player = connection == null ? null : connection.getPlayerInfoIgnoreCase(input);
            if (player != null) {
              id = player.getProfile().id().toString();
              name = player.getProfile().name();
            } else id = java.util.UUID.fromString(input).toString();
            if (draft.blocked.size() >= 500)
              throw new IllegalArgumentException("Maximum 500 blocked players");
            draft.blocked.put(id, name);
            rebuildWidgets();
          } catch (Exception error) {
            notice = "Enter an online player name or a valid UUID.";
          }
        });
    if (draft.blocked.isEmpty()) label("No players blocked.", 0, 106);
    int y = 110;
    for (var entry : new java.util.ArrayList<>(draft.blocked.entrySet())) {
      String id = entry.getKey();
      action(
          "Unblock " + entry.getValue(),
          0,
          y,
          bodyW,
          () -> {
            draft.blocked.remove(id);
            rebuildWidgets();
          });
      y += 34;
    }
  }

  private void choosePreset() {
    try {
      var choices = new java.util.ArrayList<ChoiceScreen.Choice<String>>();
      choices.add(
          new ChoiceScreen.Choice<>(
              "Current celebration", app.store.config.jobs.celebration.sound, "Edits", ""));
      for (var p : new EditPresetStore(app.store.directory()).list())
        choices.add(new ChoiceScreen.Choice<>(p.name(), p.settings().sound, "Edits", p.id()));
      minecraft.gui.setScreen(
          new ChoiceScreen<>(
              app,
              this,
              "Choose an edit to share",
              choices,
              id -> {
                presetId = id;
                presetName =
                    choices.stream()
                        .filter(p -> p.value().equals(id))
                        .findFirst()
                        .orElseThrow()
                        .label();
                minecraft.gui.setScreen(this);
              }));
    } catch (Exception e) {
      notice = e.getMessage();
    }
  }

  private CelebrationSettings selectedSettings() throws Exception {
    return presetId.isEmpty()
        ? app.store.config.jobs.celebration
        : new EditPresetStore(app.store.directory()).get(presetId).settings();
  }

  private void sendPreset(boolean aimed) {
    try {
      app.shared.send(value("player").strip(), aimed, selectedSettings());
    } catch (Exception e) {
      notice = e.getMessage();
    }
  }

  private void watchPreset(boolean aimed, EditScene scene) {
    try {
      app.shared.watch(aimed, selectedSettings(), scene);
    } catch (Exception e) {
      notice = e.getMessage();
    }
  }

  private void placeHere() {
    var p = minecraft.player;
    if (p == null) {
      notice = "Join a world first.";
      return;
    }
    values.put("avatarX", Double.toString(p.getX()));
    values.put("avatarY", Double.toString(p.getY()));
    values.put("avatarZ", Double.toString(p.getZ()));
    values.put("avatarYaw", Float.toString(net.minecraft.util.Mth.wrapDegrees(p.getYRot())));
    rebuildWidgets();
  }

  private EditScene scene() {
    if (minecraft.level == null) throw new IllegalArgumentException("Join a world first");
    var s =
        new EditScene(
            "00000000-0000-0000-0000-000000000000",
            value("avatarName").strip(),
            minecraft.level.dimension().identifier().toString(),
            Double.parseDouble(value("avatarX")),
            Double.parseDouble(value("avatarY")),
            Double.parseDouble(value("avatarZ")),
            Float.parseFloat(value("avatarYaw")),
            Float.parseFloat(value("avatarPitch")),
            Float.parseFloat(value("yawSpeed")),
            Float.parseFloat(value("pitchSpeed")));
    s.validate();
    return s;
  }

  @Override
  public void tick() {
    super.tick();
    var i = app.shared.invitation;
    String next = i == null ? "" : i.id + ":" + i.state;
    if (!next.equals(state)) {
      state = next;
      rebuildWidgets();
    }
  }

  @Override
  protected void commit() {
    draft.relayUrl = value("url");
    draft.volumeLimit = Float.parseFloat(value("volume"));
    draft.terrainRadius = (int) number("radius", 8, 48);
    if (minecraft.level != null) draft.avatar = scene();
    app.shared.configure(draft);
    app.save();
    onClose();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
    if (!lastStatus.equals(app.shared.status)) {
      lastStatus = app.shared.status;
      notice = lastStatus;
    }
    super.extractRenderState(g, mx, my, dt);
    dev.asarix.skyblockflow.EditTransferOverlay.render(g, app.shared.transfer, true);
  }
}
