package dev.asarix.skyblockflow;

import com.mojang.blaze3d.platform.NativeImage;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.ui.Ui;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;

/** Compact level-up cards and client-only firework sparks. Sends no entity packets. */
public final class CelebrationService {
  private final SkyblockFlowClient app;
  private final Map<String, Integer> celebrated = new HashMap<>();
  private long started, lastBurst;
  private CelebrationSettings active;
  private String title = "", subtitle = "", cardJob = "";
  private int cardLevel;
  private Identifier texture;
  private int imageWidth, imageHeight;
  private BassAnalyzer bass;
  private BeatEnvelope envelope;
  private long punchStarted = Long.MIN_VALUE / 2;
  private boolean audioClockAligned, shared, local;
  private EditAvatar avatar;
  private RemoteScenes.Prepared remote;

  public RemoteScenes.Prepared remoteScene() {
    return visibleNow() ? remote : null;
  }

  public void showRemote(
      CelebrationSettings settings,
      RemoteScenes.Prepared scene,
      SongAnalysis song,
      long seed,
      long start) {
    var display = ConfigStore.copy(settings, CelebrationSettings.class);
    display.edit.cameraCuts = true;
    display.edit.cameraIntroDistance =
        Math.max(8, Math.min(display.edit.cameraIntroDistance, scene.radius() - 2));
    display.edit.cameraMaxDistance = Math.min(display.edit.cameraMaxDistance, Math.max(2, scene.radius() - 2));
    display.edit.cameraMinDistance =
        Math.min(display.edit.cameraMinDistance, display.edit.cameraMaxDistance);
    showShared(display, scene.scene().name, scene.subject(), song, seed, start);
    remote = scene;
  }

  private net.minecraft.world.entity.Entity subject;

  public boolean sharedActive() {
    return active != null && shared;
  }

  public boolean localActive() {
    return active != null && local;
  }

  private boolean featureEnabled() {
    return local || app.store.config.enabled(feature());
  }

  private boolean featureVisible() {
    return local || app.store.config.visible(feature());
  }

  private Feature feature() {
    return shared ? Feature.SHARED_EDITS : Feature.JOBS;
  }

  public net.minecraft.world.entity.Entity subject() {
    return subject == null ? Minecraft.getInstance().player : subject;
  }

  private void alignAudioClock() {
    if (active == null || bass == null) return;
    if (bass.awaitingPlayback()) started = now();
    else if (!audioClockAligned && bass.clockStarted()) {
      started = now() - bass.positionMs();
      audioClockAligned = true;
    }
  }

  private OrbitShots shots;
  private Runnable stopEditSound = () -> {};

  public record EditFrame(
      double zoom, float pitch, float yaw, float roll, double pulse, double fade) {
    public static final EditFrame NONE = new EditFrame(1, 0, 0, 0, 0, 0);
  }

  public float bassEnergy() {
    return bass == null ? 0 : bass.energy();
  }

  public long bassHits() {
    return bass == null ? 0 : bass.beats();
  }

  public void previewBeat() {
    triggerBeat(now());
  }

  private void triggerBeat(long time) {
    punchStarted = time;
    if (envelope != null) envelope.trigger(time);
    if (shots != null && active.edit.cameraCuts && time - started >= introDuration())
      shots.cut(time);
  }

  private boolean visibleNow() {
    alignAudioClock();
    var mc = Minecraft.getInstance();
    return active != null
        && !(bass != null && bass.waitingScheduledStart())
        && mc.player != null
        && mc.level != null
        && now() - started < duration()
        && featureVisible()
        && featureEnabled();
  }

  /** A render-only F1 override; the user's underlying HUD toggle is never changed. */
  public boolean hideMinecraftHud() {
    return visibleNow() && active.hideMinecraftHud;
  }

  public boolean hideOverlays() {
    return visibleNow() && active.hideOverlays;
  }

  public boolean orbitActive() {
    var mc = Minecraft.getInstance();
    return visibleNow()
        && active.edit.enabled
        && (active.edit.cameraCuts || active.edit.cameraIntro)
        && cameraReturnProgress() < 1
        && mc.getCameraEntity() == mc.player;
  }

  private SongOutro.Timing outro() {
    return bass == null ? SongOutro.fallback(active.durationMs) : bass.outro(active.durationMs);
  }

  private long duration() {
    return active.edit.enabled
            && (active.edit.cameraCuts || active.edit.cameraIntro)
            && active.edit.cameraReturn
        ? Math.max(active.durationMs, outro().endMs())
        : active.durationMs;
  }

  public double cameraReturnProgress() {
    if (active == null
        || !active.edit.enabled
        || !(active.edit.cameraCuts || active.edit.cameraIntro)
        || !active.edit.cameraReturn) return 0;
    return outro().progress(now() - started);
  }

  private long introDuration() {
    if (active == null || !active.edit.cameraIntro) return 0;
    long available = active.edit.cameraReturn ? outro().startMs() : duration();
    return Math.min(active.edit.cameraIntroMs, Math.max(0, available));
  }

  public int cameraCuts() {
    return shots == null ? 0 : shots.cuts();
  }

  public OrbitShots.Shot cameraShot() {
    if (!orbitActive()) return null;
    editFrame(); // Consume a decoded bass hit before choosing this frame's camera position.
    var shot =
        shots.sample(
            cameraReturnProgress() > 0 ? Math.min(now(), started + outro().startMs()) : now());
    if (introDuration() > 0 && cameraReturnProgress() == 0)
      shot = new OrbitShots.Shot(
          shot.azimuth(),
          shot.elevation(),
          CameraIntro.distance(
              shot.distance(),
              active.edit.cameraIntroDistance,
              now() - started,
              introDuration(),
              active.edit.cameraIntroCurve,
              active.edit.cameraIntroAccelerates));
    if (remote != null)
      shot = new OrbitShots.Shot(shot.azimuth(), shot.elevation(),
          Math.min(shot.distance(), Math.max(2, remote.radius() - 2)));
    return shot;
  }

  public EditFrame editFrame() {
    alignAudioClock();
    var mc = Minecraft.getInstance();
    if (active == null
        || bass != null && bass.waitingScheduledStart()
        || !active.edit.enabled
        || mc.player == null
        || mc.level == null
        || !featureVisible()
        || !featureEnabled()) return EditFrame.NONE;
    long time = now(), age = time - started;
    if (age < 0 || age >= duration()) return EditFrame.NONE;
    var e = active.edit;
    if (bass != null) {
      long playhead = bass.positionMs();
      for (var hit : bass.drainHits())
        if (e.bassReactive) triggerBeat(time - Math.max(0, playhead - hit.timeMs()));
    }
    double pulse = e.bassReactive ? envelope.value(time) : 0;
    if (e.bassReactive && e.punchEnabled && time >= punchStarted) {
      double punch = Math.max(0, 1 - (time - punchStarted) / (double) e.punchMs);
      pulse += punch * e.punchStrength;
    }
    double fade =
        Math.min(
            e.fadeInMs == 0 ? 1 : BeatEnvelope.ease(age / (double) e.fadeInMs),
            e.fadeOutMs == 0 ? 1 : BeatEnvelope.ease((duration() - age) / (double) e.fadeOutMs));
    fade *= 1 - cameraReturnProgress();
    double motion = 1 + (e.motionMultiplier - 1) * pulse;
    double t = age / 1000.0 * e.shakeHz * Math.PI * 2;
    float amplitude = (float) (e.shakeDegrees * motion * fade);
    float pitch = amplitude * (float) (.7 * Math.sin(t) + .3 * Math.sin(t * 1.73));
    float yaw = amplitude * (float) (.7 * Math.sin(t * .83 + .5) + .3 * Math.sin(t * 1.31));
    float roll = (float) (e.rollDegrees * motion * fade * Math.sin(t * .61));
    double zoom = 1 + (e.zoom - 1) * fade * (e.beatZoom ? motion : 1);
    return new EditFrame(zoom, pitch, yaw, roll, pulse, fade);
  }

  /** Tint and lens accents go underneath the HUD, preserving readable menus and overlays. */
  public void renderEdit(GuiGraphicsExtractor g) {
    var frame = editFrame();
    if (frame.fade() <= 0) return;
    var e = active.edit;
    int w = g.guiWidth(), h = g.guiHeight();
    if (e.colorGrade) g.fill(0, 0, w, h, opacity(e.tintStrength * frame.fade(), e.tintColor));
    if (e.lightPulse)
      g.fill(0, 0, w, h, opacity(e.pulseStrength * frame.pulse() * frame.fade(), 0xfff0d8));
    if (e.vignette) {
      int lastX = 0, lastY = 0;
      for (int i = 1; i <= 32; i++) {
        int x = w * i / 110, y = h * i / 110;
        int color =
            opacity(e.vignetteStrength * frame.fade() * Math.pow(1 - (i - 1) / 32.0, 2), 0x080716);
        g.fill(lastX, lastY, w - lastX, y, color);
        g.fill(lastX, h - y, w - lastX, h - lastY, color);
        g.fill(lastX, y, x, h - y, color);
        g.fill(w - x, y, w - lastX, h - y, color);
        lastX = x;
        lastY = y;
      }
    }
    if (e.letterbox) {
      int bar = (int) (h * e.letterboxHeight * frame.fade());
      g.fill(0, 0, w, bar, 0xff050508);
      g.fill(0, h - bar, w, h, 0xff050508);
    }
    g.nextStratum();
  }

  private static int opacity(double amount, int color) {
    return (int) (Math.clamp(amount, 0, 1) * 255) << 24 | color & 0xffffff;
  }

  public boolean skipEdit() {
    if (active == null || !active.edit.enabled) return false;
    stop();
    return true;
  }

  public void renderSkipHint(GuiGraphicsExtractor g) {
    if (!visibleNow() || !active.edit.enabled) return;
    var font = Minecraft.getInstance().font;
    String before = "press ", key = "escape", after = " to skip";
    int keyWidth = font.width(key) + 8;
    int width = font.width(before) + keyWidth + font.width(after);
    int x = Math.max(4, g.guiWidth() - width - 12), y = g.guiHeight() - 26;
    int keyX = x + font.width(before);
    g.text(font, before, x, y + 3, 0xffeeeeee, true);
    // An outlined keycap leaves the world visible through the entire hint.
    int edge = 0xcceeeeee;
    g.fill(keyX + 2, y, keyX + keyWidth - 2, y + 1, edge);
    g.fill(keyX, y + 2, keyX + 1, y + 12, edge);
    g.fill(keyX + keyWidth - 1, y + 2, keyX + keyWidth, y + 12, edge);
    g.fill(keyX + 2, y + 13, keyX + keyWidth - 2, y + 14, edge);
    g.fill(keyX + 3, y + 15, keyX + keyWidth - 3, y + 16, 0x88eeeeee);
    g.text(font, key, keyX + 4, y + 3, 0xffffffff, true);
    g.text(font, after, keyX + keyWidth, y + 3, 0xffeeeeee, true);
  }

  public void stop() {
    active = null;
    remote = null;
    if (avatar != null) avatar.close();
    avatar = null;
    shared = false;
    local = false;
    subject = null;
    bass = null;
    envelope = null;
    shots = null;
    stopEditSound.run();
    stopEditSound = () -> {};
    releaseImage();
  }

  public CelebrationService(SkyblockFlowClient app) {
    this.app = app;
  }

  public boolean active() {
    return active != null;
  }

  public void positionCard(CelebrationSettings settings) {
    if (active == null) return;
    active.customCardPosition = settings.customCardPosition;
    active.cardX = Math.clamp(settings.cardX, 0, 1);
    active.cardY = Math.clamp(settings.cardY, 0, 1);
  }

  /** Same rectangle for rendering and preview hit testing, including the entry animation. */
  public OverlayLayout.Rect cardBounds(int sw, int sh) {
    if (!visibleNow() || !active.showCard) return null;
    boolean cinematic = orbitActive();
    int w = Math.min(cinematic ? 292 : 310, Math.max(1, sw - 24)),
        h = Math.min(sh, cinematic ? 76 : 90);
    double age = (now() - started) / 1000.0, duration = duration() / 1000.0;
    double fade = Math.clamp(Math.min(age / .35, (duration - age) / .65), 0, 1);
    double x = (sw - w) * (active.customCardPosition ? active.cardX : .5);
    double y =
        active.customCardPosition
            ? (sh - h) * active.cardY
            : Math.clamp(cinematic ? 12 : sh * .13, 0, sh - h);
    if (!active.customCardPosition && active.animated)
      y -= Math.min(y, (1 - fade) * (1 - fade) * 10);
    return new OverlayLayout.Rect(x, y, w, h);
  }

  public Path images() {
    return app.store.directory().resolve("celebrations");
  }

  private long now() {
    return System.nanoTime() / 1000000;
  }

  public void message(String text) {
    if (sharedActive()) return;
    var level = JobMessages.levelUp(text);
    if (level == null
        || !app.store.config.enabled(Feature.JOBS)
        || !app.store.config.jobs.celebration.enabled) return;
    if (celebrated.getOrDefault(level.job(), 0) >= level.level()) return;
    celebrated.put(level.job(), level.level());
    show(app.store.config.jobs.celebration, level.job(), level.level(), false);
  }

  public void reset() {
    stop();
    celebrated.clear();
  }

  private void releaseImage() {
    if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);
    texture = null;
  }

  public String importImage(Path path) throws java.io.IOException {
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
        || Files.size(path) > 8 * 1024 * 1024)
      throw new java.io.IOException("Choose a PNG image smaller than 8 MB");
    PngSafety.validate(path);
    try (var input = Files.newInputStream(path);
        var image = NativeImage.read(input)) {
      if (image.getWidth() > 2048 || image.getHeight() > 2048)
        throw new java.io.IOException("Image must fit within 2048 × 2048 pixels");
    }
    Files.createDirectories(images());
    var destination = images().resolve(path.getFileName());
    if (Files.exists(destination) && Files.isSameFile(path, destination))
      return path.getFileName().toString();
    for (int n = 2; Files.exists(destination); n++)
      destination = images().resolve(n + "-" + path.getFileName());
    Files.copy(path, destination);
    return destination.getFileName().toString();
  }

  private void loadImage(String name) {
    releaseImage();
    if (name.isBlank()) return;
    try {
      var directory = images().toRealPath();
      var file = directory.resolve(name).normalize().toRealPath();
      if (!file.startsWith(directory) || Files.size(file) > 8 * 1024 * 1024)
        throw new java.io.IOException("Invalid image path");
      PngSafety.validate(file);
      NativeImage image;
      try (var input = Files.newInputStream(file)) {
        image = NativeImage.read(input);
      }
      if (image.getWidth() > 2048 || image.getHeight() > 2048) {
        image.close();
        throw new java.io.IOException("Image too large");
      }
      imageWidth = image.getWidth();
      imageHeight = image.getHeight();
      texture = Identifier.fromNamespaceAndPath("skyblockflow", "celebration_custom");
      Minecraft.getInstance()
          .getTextureManager()
          .register(texture, new DynamicTexture(() -> "SkyblockFlow celebration", image));
    } catch (Exception e) {
      texture = null;
      app.error("Celebration image: " + e.getMessage());
    }
  }

  public void show(CelebrationSettings settings, String job, int level, boolean preview) {
    var style = preview ? null : app.store.config.alarmStyles.get(AlarmKind.LEVEL_UP);
    if (style != null && !style.enabled) return;
    ConfigStore.validateCelebration(settings);
    audioClockAligned = false;
    punchStarted = Long.MIN_VALUE / 2;
    stop();
    active = ConfigStore.copy(settings, CelebrationSettings.class);
    started = now();
    lastBurst = 0;
    bass = new BassAnalyzer(active.edit);
    envelope = new BeatEnvelope(active.edit.attackMs, active.edit.releaseMs);
    var player = Minecraft.getInstance().player;
    shots =
        new OrbitShots(active.edit, player == null ? 0 : -player.getYRot(), started, new Random());
    cardJob = job;
    cardLevel = level;
    title =
        settings.text.equals(new CelebrationSettings().text)
            ? JobXp.name(job)
            : settings.text.replace("{job}", JobXp.name(job)).replace("{level}", "" + level);
    subtitle = settings.subtitle.replace("{job}", JobXp.name(job)).replace("{level}", "" + level);
    if (style != null) {
      String originalTitle = title, originalSub = subtitle;
      title = style.render(style.title, originalTitle, originalSub);
      if (!style.subtitle.equals("{subtitle}")) {
        subtitle = style.render(style.subtitle, originalTitle, originalSub);
        active.subtitle = style.subtitle;
      }
      if (!style.actionBar.isBlank() && app.store.config.visible(AlarmKind.LEVEL_UP.feature))
        Minecraft.getInstance()
            .gui
            .hud
            .setOverlayMessage(
                dev.asarix.skyblockflow.ui.DisplayText.literal(
                    style.render(style.actionBar, originalTitle, originalSub)),
                false);
      if (style.customSound) {
        active.soundKind = style.soundKind;
        active.sound = style.sound;
        active.volume = style.volume;
        active.pitch = style.pitch;
      }
    }
    loadImage(settings.image);
    if (active.edit.enabled) stopEditSound = app.sounds.playCelebration(active, bass, preview);
    else if (preview) app.sounds.play(settings);
    else app.sounds.playAlert(active, AlarmKind.LEVEL_UP);
    app.event("Level up · " + JobXp.name(job) + " " + level);
  }

  public void showShared(
      CelebrationSettings settings,
      String name,
      net.minecraft.world.entity.Entity entity,
      SongAnalysis analysis,
      long seed,
      long startNanos) {
    ConfigStore.validateCelebration(settings);
    stop();
    shared = true;
    subject = entity;
    active = ConfigStore.copy(settings, CelebrationSettings.class);
    active.edit.enabled = true;
    started = startNanos / 1000000;
    lastBurst = 0;
    audioClockAligned = false;
    punchStarted = Long.MIN_VALUE / 2;
    bass = new BassAnalyzer(active.edit);
    bass.usePreviewAnalysis(analysis);
    bass.schedule(startNanos);
    envelope = new BeatEnvelope(active.edit.attackMs, active.edit.releaseMs);
    // A common seed and absolute heading keep both players' camera cuts identical.
    shots = new OrbitShots(active.edit, 0, started, new Random(seed));
    title =
        active.text.equals(new CelebrationSettings().text)
            ? name
            : active.text.replace("{entity}", name).replace("{job}", name).replace("{level}", "");
    subtitle =
        active.subtitle.equals(new CelebrationSettings().subtitle)
            ? "Shared edit"
            : active
                .subtitle
                .replace("{entity}", name)
                .replace("{job}", name)
                .replace("{level}", "");
    loadImage(active.image);
    stopEditSound = app.sounds.playCelebration(active, bass, true);
  }

  public void showScene(
      CelebrationSettings settings,
      String name,
      net.minecraft.world.entity.Entity entity,
      SongAnalysis analysis,
      long seed,
      long startNanos,
      boolean localPlayback,
      EditScene scene,
      com.mojang.authlib.GameProfile profile) {
    var mc = Minecraft.getInstance();
    if (scene != null && !EditAvatar.available(scene))
      throw new IllegalArgumentException(
          "Avatar position must be loaded, in this dimension, within 256 blocks");
    showShared(settings, name, entity, analysis, seed, startNanos);
    local = localPlayback;
    if (local)
      subtitle = active.subtitle.equals(new CelebrationSettings().subtitle) ? "" : subtitle;
    if (scene != null) {
      try {
        avatar = new EditAvatar(mc.level, scene, profile);
        mc.level.addEntity(avatar);
        subject = avatar;
      } catch (RuntimeException error) {
        stop();
        throw error;
      }
    }
  }

  public void tick() {
    if (shared && bass != null && bass.playbackFailed()) {
      stop();
      return;
    }
    alignAudioClock();
    if (active == null || bass != null && bass.waitingScheduledStart()) return;
    var mc = Minecraft.getInstance();
    if (now() - started >= duration() || mc.player == null || !featureEnabled()) {
      stop();
      return;
    }
    if (subject != null && (subject.isRemoved() || subject.level() != mc.level)
        || avatar != null && !EditAvatar.available(avatar.scene())) {
      stop();
      return;
    }
    if (avatar != null) avatar.animate(Math.max(0, now() - started) / 1000.0);
    if (remote != null) remote.animate(Math.max(0, now() - started) / 1000.0);
    if (!active.fireworks || mc.level == null || !featureVisible() || now() - lastBurst < 550)
      return;
    lastBurst = now();
    double angle = (now() - started) / 700.0;
    var target = subject();
    double x = target.getX() + Math.cos(angle) * 2.5,
        y = target.getY() + 2.8,
        z = target.getZ() + Math.sin(angle) * 2.5;
    for (int i = 0; i < 32; i++) {
      double az = i * Math.PI * 2 / 32, vy = ((i * 7) % 11 - 5) * .035;
      mc.level.addParticle(
          ParticleTypes.FIREWORK, x, y, z, Math.cos(az) * .17, vy, Math.sin(az) * .17);
    }
  }

  public void render(GuiGraphicsExtractor g) {
    if (active == null || !featureVisible()) return;
    double age = (now() - started) / 1000.0, duration = duration() / 1000.0;
    double fade = Math.min(1, Math.min(age / .35, (duration - age) / .65));
    if (fade <= 0) return;
    boolean cinematic = orbitActive();
    var bounds = cardBounds(g.guiWidth(), g.guiHeight());
    if (bounds == null) return;
    int w = (int) bounds.width(), h = (int) bounds.height();
    int x = (int) bounds.x(), y = (int) bounds.y();
    int accent =
        shared
            ? 0xA6B5FF
            : cardLevel % 5 == 0
                ? 0xE7C583
                : switch (cardJob) {
                  case "chasseur" -> 0xF29AA9;
                  case "fermier", "bucheron" -> 0x92D7AE;
                  case "mineur", "pecheur" -> 0x8BCBDD;
                  case "artisan" -> 0xEBB48C;
                  default -> 0xA6B5FF;
                };
    var hud = app.store.config.hud;
    var paint =
        new dev.asarix.skyblockflow.ui.HudPaint(
            hud.theme(dev.asarix.skyblockflow.core.HudSettings.Panel.CELEBRATION), hud.legacy);
    if (!hud.legacy) {
      accent = paint.accent() & 0xffffff;
      paint.panel(g, x, y, w, h, fade);
    } else {
      // Quiet depth and a thin frame keep the card readable against bright worlds.
      Ui.rounded(g, x - 2, y + 3, w + 4, h + 3, 12, rgba(0x000000, fade * .13));
      Ui.rounded(g, x, y, w, h, 10, rgba(0x42505F, fade * .8));
      Ui.rounded(g, x + 1, y + 1, w - 2, h - 2, 9, rgba(0x101923, fade * .97));
      Ui.rounded(g, x + 1, y + 11, 2, h - 22, 1, rgba(accent, fade * .85));
    }
    int badgeSize = cinematic ? 46 : 54;
    int badgeX = x + 14, badgeY = y + (h - badgeSize) / 2 - 2;
    Ui.rounded(g, badgeX, badgeY, badgeSize, badgeSize, 8, rgba(accent, fade * .13));
    if (texture != null) {
      float scale = Math.min((badgeSize - 8f) / imageWidth, (badgeSize - 8f) / imageHeight);
      int iw = Math.max(1, Math.round(imageWidth * scale)),
          ih = Math.max(1, Math.round(imageHeight * scale));
      g.blit(
          RenderPipelines.GUI_TEXTURED,
          texture,
          badgeX + (badgeSize - iw) / 2,
          badgeY + (badgeSize - ih) / 2,
          0,
          0,
          iw,
          ih,
          imageWidth,
          imageHeight,
          imageWidth,
          imageHeight,
          rgba(0xFFFFFF, fade));
    } else {
      String number = shared ? "▶" : Integer.toString(cardLevel);
      float scale =
          Math.min(cinematic ? 2.2f : 2.7f, (badgeSize - 10f) / Math.max(1, paint.width(number)));
      paint.text(
          g,
          shared ? "PLAY" : "LVL",
          badgeX + (badgeSize - paint.width(shared ? "PLAY" : "LVL")) / 2,
          badgeY + 8,
          rgba(accent, fade * .85));
      scaledText(
          paint,
          g,
          number,
          badgeX + (badgeSize - paint.width(number) * scale) / 2,
          badgeY + (cinematic ? 20 : 23),
          scale,
          rgba(hud.legacy ? 0xF3F6FA : paint.ink() & 0xffffff, fade));
    }
    int textX = badgeX + badgeSize + 15, available = Math.max(0, x + w - 16 - textX);
    int top = y + (cinematic ? 14 : 19);
    String eyebrow =
        shared
            ? (local ? "YOUR EDIT" : "SHARED EDIT")
            : cardLevel % 5 == 0 ? "MILESTONE REACHED" : "LEVEL UP";
    if (!shared && texture != null) eyebrow += "  ·  " + cardLevel;
    paint.text(g, paint.trim(eyebrow, available), textX, top, rgba(accent, fade));
    float headingScale = cinematic ? 1.3f : 1.5f;
    scaledText(
        paint,
        g,
        paint.trim(title, (int) (available / headingScale)),
        textX,
        top + 15,
        headingScale,
        rgba(hud.legacy ? 0xF3F6FA : paint.ink() & 0xffffff, fade));
    String detail = subtitle;
    if (!shared && active.subtitle.equals(new CelebrationSettings().subtitle))
      detail = "Level " + Math.max(0, cardLevel - 1) + "  →  " + cardLevel;
    paint.text(
        g,
        paint.trim(detail, available),
        textX,
        top + (cinematic ? 34 : 38),
        rgba(hud.legacy ? 0xA7B5C5 : paint.secondary() & 0xffffff, fade));
    int trackX = x + 16, trackY = y + h - 8, trackW = Math.max(0, w - 32);
    Ui.rounded(g, trackX, trackY, trackW, 1, 0, rgba(0x667587, fade * .25));
    int remaining = (int) (trackW * Math.clamp(1 - age / duration, 0, 1));
    Ui.rounded(g, trackX, trackY, remaining, 1, 0, rgba(accent, fade * .7));
  }

  private static int rgba(int rgb, double opacity) {
    return (int) (255 * Math.clamp(opacity, 0, 1)) << 24 | rgb;
  }

  private static void scaledText(
      dev.asarix.skyblockflow.ui.HudPaint paint,
      GuiGraphicsExtractor g,
      String text,
      float x,
      float y,
      float scale,
      int color) {
    g.pose().pushMatrix();
    g.pose().translate(x, y);
    g.pose().scale(scale, scale);
    paint.text(g, text, 0, 0, color);
    g.pose().popMatrix();
  }
}
