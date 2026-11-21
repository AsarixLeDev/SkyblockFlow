package dev.asarix.skyblockflow;

import dev.asarix.skyblockflow.core.BassAnalyzer;
import dev.asarix.skyblockflow.core.Models.*;
import dev.asarix.skyblockflow.core.SongAnalysis;
import dev.asarix.skyblockflow.core.SongAnalysisCache;
import dev.asarix.skyblockflow.core.HitTimeline;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.AudioDeviceBase;
import javazoom.jl.player.JavaSoundAudioDevice;
import javazoom.jl.player.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

public final class SoundService implements AutoCloseable {
  private final SkyblockFlowClient app;
  private final SongAnalysisCache analyses;
  private final boolean ownsAnalyses;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "SkyblockFlow audio");
            t.setDaemon(true);
            return t;
          });
  private final AtomicLong generation = new AtomicLong();
  private volatile Player player;
  private volatile SourceDataLine directLine;
  private Future<?> pending;
  private SimpleSoundInstance vanilla;
  private final List<SimpleSoundInstance> alarmVanilla = new ArrayList<>();
  private volatile boolean alarmActive;
  private SoundService preview;
  private BassAnalyzer reactiveBass;

  public SoundService(SkyblockFlowClient app) {
    this.app = app;
    analyses = new SongAnalysisCache(app.store.sounds());
    ownsAnalyses = true;
  }

  private SoundService(SkyblockFlowClient app, SongAnalysisCache analyses) {
    this.app = app;
    this.analyses = analyses;
    ownsAnalyses = false;
  }

  public void forgetSharedAudio() { analyses.forgetSharedAudio(); }

  public CompletableFuture<SongAnalysis> analyzeSong(
      SoundSettings sound, CelebrationEditSettings edit) {
    if (sound.soundKind != SoundKind.MP3)
      return CompletableFuture.failedFuture(
          new IOException(
              "Song analysis is available for MP3 files; vanilla variants use live detection."));
    return analyses.request(sound.sound, edit, sound.pitch);
  }

  public CompletableFuture<HitTimeline> songTimeline(CelebrationSettings settings) {
    return analyses.timeline(settings.sound, settings.edit, settings.pitch);
  }
  public CompletableFuture<SongAnalysis> automaticSong(CelebrationSettings settings) {
    return analyses.requestAutomatic(settings.sound, settings.edit, settings.pitch);
  }
  public CompletableFuture<Void> saveTimeline(String name, HitTimeline doc) {
    return analyses.saveTimeline(name, doc);
  }
  public CompletableFuture<HitTimeline> importTimeline(Path source, String name, long duration) {
    return analyses.importTimeline(source, name, duration);
  }
  public CompletableFuture<Path> exportTimeline(Path target, String name, HitTimeline doc) {
    return analyses.exportTimeline(target, name, doc);
  }

  public boolean isCustomPlaying() {
    return pending != null && !pending.isDone() || preview != null && preview.isCustomPlaying();
  }

  public boolean isAlarmActive() {
    return alarmActive;
  }

  public boolean isSystemPlaying() {
    SourceDataLine line = directLine;
    return line != null && line.isOpen();
  }

  public List<String> files() {
    try (var paths = Files.list(app.store.sounds())) {
      return paths
          .filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .filter(s -> s.toLowerCase(Locale.ROOT).endsWith(".mp3"))
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .toList();
    } catch (IOException e) {
      app.error("Cannot read sound folder: " + e.getMessage());
      return List.of();
    }
  }

  public String importFile(Path source) throws IOException {
    String name = source.getFileName().toString();
    if (!name.toLowerCase(Locale.ROOT).endsWith(".mp3"))
      throw new IOException("Choose an .mp3 file");
    if (Files.size(source) > 32L * 1024 * 1024)
      throw new IOException("MP3 must be smaller than 32 MB");
    Path destination = app.store.sounds().resolve(name);
    if (Files.exists(destination) && Files.isSameFile(source, destination)) {
      analyses.request(
          name, app.store.config.jobs.celebration.edit, app.store.config.jobs.celebration.pitch);
      return name;
    }
    String base = name.substring(0, name.length() - 4);
    for (int n = 2; Files.exists(destination); n++)
      destination = app.store.sounds().resolve(base + "-" + n + ".mp3");
    Files.copy(source, destination);
    analyses.request(
        destination.getFileName().toString(),
        app.store.config.jobs.celebration.edit,
        app.store.config.jobs.celebration.pitch);
    return destination.getFileName().toString();
  }

  public synchronized void stop() {
    generation.incrementAndGet();
    if (reactiveBass != null) {
      reactiveBass.cancelPlayback();
      reactiveBass.endPlayback();
      reactiveBass = null;
    }
    alarmActive = false;
    if (player != null) {
      player.close();
      player = null;
    }
    if (directLine != null) {
      directLine.close();
      directLine = null;
    }
    if (pending != null) {
      pending.cancel(true);
      pending = null;
    }
    if (vanilla != null) {
      Minecraft.getInstance().getSoundManager().stop(vanilla);
      vanilla = null;
    }
    for (SimpleSoundInstance sound : alarmVanilla)
      Minecraft.getInstance().getSoundManager().stop(sound);
    alarmVanilla.clear();
  }

  public void play(SoundSettings rule) {
    if (preview == null) preview = new SoundService(app, analyses);
    preview.play(rule, false);
  }

  public void playAlert(SoundSettings rule) {
    if (!app.alertsAllowed()) return;
    playAlert(rule, AlarmKind.CHAT);
  }

  public void playAlert(SoundSettings rule, AlarmKind kind) {
    if (kind != AlarmKind.TIMER && kind != AlarmKind.LEVEL_UP && !app.alertsAllowed()) return;
    boolean looping = false;
    if (alarmActive && !looping) play(rule);
    else play(rule, looping);
  }

  /** Returns a cancellation scoped to this playback, never a later alarm or song. */
  public Runnable playCelebration(CelebrationSettings rule, BassAnalyzer bass, boolean test) {
    boolean looping = false;
    SoundService channel = this;
    if (test || alarmActive && !looping) {
      if (preview == null) preview = new SoundService(app, analyses);
      channel = preview;
    }
    channel.play(rule, looping, rule.edit, bass);
    SoundService owner = channel;
    long ticket = owner.generation.get();
    return () -> {
      synchronized (owner) {
        // Cancel only this playback, never a later song.
        if (!looping && owner.generation.get() == ticket) owner.stop();
      }
    };
  }

  public void stopPreview() {
    if (preview != null) preview.stop();
  }

  public void testAlarm(SoundSettings rule) {
    play(rule, false);
  }

  private void play(SoundSettings rule, boolean looping) {
    play(rule, looping, null, null);
  }

  private void play(
      SoundSettings rule, boolean looping, CelebrationEditSettings edit, BassAnalyzer bass) {
    stop();
    reactiveBass = bass;
    int repetitions = 1;
    Minecraft mc = Minecraft.getInstance();
    SoundKind kind = rule.soundKind;
    String soundName = rule.sound;
    float volume = rule.volume * (edit == null ? 1 : edit.volumeMultiplier);
    if (looping && (kind == SoundKind.SILENT || volume == 0)) {
      kind = SoundKind.VANILLA;
      soundName = "minecraft:block.note_block.pling";
      volume = 0.8f;
    }
    if (kind == SoundKind.SILENT) return;
    alarmActive = looping;
    if (kind == SoundKind.VANILLA) {
      Identifier id = Identifier.tryParse(soundName);
      if (id == null) {
        alarmActive = false;
        app.error("Invalid sound ID");
        return;
      }
      if (looping || edit != null) {
        float multiplier =
            looping
                ? repetitions
                : mc.options.getSoundSourceVolume(SoundSource.MASTER)
                    * mc.options.getSoundSourceVolume(SoundSource.UI);
        playSystemVanilla(mc, id, volume, rule.pitch, multiplier, looping, bass);
      } else {
        vanilla =
            SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), rule.pitch, volume);
        mc.getSoundManager().play(vanilla);
      }
      return;
    }
    long ticket = generation.get();
    float outputVolume =
        volume
            * (looping
                ? repetitions
                : mc.options.getSoundSourceVolume(SoundSource.MASTER)
                    * mc.options.getSoundSourceVolume(SoundSource.UI));
    String fileName = soundName;
    if (bass != null) bass.expectPlayback();
    pending =
        worker.submit(
            () -> {
              try {
                Path directory = app.store.sounds().toRealPath();
                Path file = directory.resolve(fileName).normalize().toRealPath();
                if (!file.startsWith(directory)
                    || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3"))
                  throw new IOException("Sound must be an MP3 inside the sounds folder");
                if (Files.size(file) > 32L * 1024 * 1024)
                  throw new IOException("MP3 is larger than 32 MB");
                if (bass != null) {
                  try {
                    bass.useAnalysis(analyses.request(fileName, edit, rule.pitch).get());
                  } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    return;
                  } catch (ExecutionException failed) {
                    /* Live detector remains available. */
                  }
                  if (ticket != generation.get()) return;
                }
                do {
                  long started = System.nanoTime();
                  try (InputStream stream = new BufferedInputStream(Files.newInputStream(file))) {
                    AudioDevice device =
                        edit != null
                            ? reactiveDevice(ticket, outputVolume, rule.pitch, bass)
                            : new JavaSoundAudioDevice() {
                              @Override
                              protected void writeImpl(short[] samples, int offset, int length)
                                  throws JavaLayerException {
                                short[] scaled = new short[length];
                                for (int i = 0; i < length; i++) {
                                  scaled[i] = boost(samples[offset + i], outputVolume);
                                }
                                super.writeImpl(scaled, 0, length);
                              }
                            };
                    Player local = new Player(stream, device);
                    synchronized (this) {
                      if (ticket != generation.get()) {
                        local.close();
                        return;
                      }
                      player = local;
                    }
                    try {
                      local.play();
                    } finally {
                      local.close();
                      synchronized (this) {
                        if (player == local) player = null;
                      }
                    }
                  }
                  if (looping && ticket == generation.get()) {
                    long remaining = 100 - (System.nanoTime() - started) / 1000000;
                    if (remaining > 0) Thread.sleep(remaining);
                  }
                } while (looping && ticket == generation.get());
              } catch (Exception e) {
                if (ticket == generation.get())
                  mc.execute(
                      () -> {
                        if (ticket == generation.get()) {
                          alarmActive = false;
                          if (bass != null) bass.cancelPlayback();
                          app.error("Cannot play MP3: " + e.getMessage());
                        }
                      });
              }
            });
  }

  private void playSystemVanilla(
      Minecraft mc,
      Identifier id,
      float volume,
      float pitch,
      float multiplier,
      boolean looping,
      BassAnalyzer bass) {
    WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(id);
    if (event == null) {
      alarmActive = false;
      app.error("Unknown vanilla sound: " + id);
      return;
    }
    RandomSource random = SoundInstance.createUnseededRandom();
    Sound sound = event.getSound(random);
    Identifier path = sound.getPath();
    float gain = volume * sound.getVolume().sample(random) * multiplier;
    float playbackPitch = pitch * sound.getPitch().sample(random);
    long ticket = generation.get();
    pending =
        worker.submit(
            () -> {
              SourceDataLine line = null;
              try {
                do {
                  try (JOrbisAudioStream audio =
                      new JOrbisAudioStream(mc.getResourceManager().open(path))) {
                    if (line == null) {
                      AudioFormat source = audio.getFormat();
                      if (source.getSampleSizeInBits() != 16 || source.isBigEndian())
                        throw new IOException("Unsupported vanilla sound format");
                      AudioFormat playback =
                          new AudioFormat(
                              source.getEncoding(),
                              source.getSampleRate() * playbackPitch,
                              source.getSampleSizeInBits(),
                              source.getChannels(),
                              source.getFrameSize(),
                              source.getFrameRate() * playbackPitch,
                              false);
                      line = AudioSystem.getSourceDataLine(playback);
                      if (bass == null) line.open(playback);
                      else
                        line.open(
                            playback,
                            Math.max(1, (int) (playback.getSampleRate() * .06))
                                * playback.getFrameSize());
                      synchronized (this) {
                        if (ticket != generation.get()) return;
                        directLine = line;
                      }
                      line.start();
                      SourceDataLine playbackLine = line;
                      if (bass != null)
                        bass.beginPlayback(
                            () -> playbackLine.getMicrosecondPosition() / 1000, playbackPitch);
                    }
                    while (ticket == generation.get()) {
                      ByteBuffer chunk = audio.read(bass == null ? 8192 : 1024);
                      if (!chunk.hasRemaining()) break;
                      short[] raw = bass == null ? null : pcm(chunk.duplicate());
                      byte[] output = boost(chunk, gain);
                      int offset = 0;
                      while (offset < output.length && ticket == generation.get())
                        offset += line.write(output, offset, output.length - offset);
                      if (bass != null && ticket == generation.get())
                        bass.accept(
                            raw,
                            0,
                            raw.length,
                            (int) audio.getFormat().getSampleRate(),
                            line.getFormat().getChannels());
                    }
                  }
                  if (ticket == generation.get()) {
                    if (looping) Thread.sleep(100);
                    else line.drain();
                  }
                } while (looping && ticket == generation.get());
              } catch (Exception e) {
                if (ticket == generation.get())
                  mc.execute(
                      () -> {
                        if (ticket == generation.get()) {
                          app.error(
                              "System audio unavailable; using Minecraft sound: " + e.getMessage());
                          if (looping)
                            playMinecraftLoop(mc, id, volume, pitch, Math.max(1, (int) multiplier));
                          else {
                            vanilla =
                                SimpleSoundInstance.forUI(
                                    SoundEvent.createVariableRangeEvent(id),
                                    pitch,
                                    Math.min(1, volume));
                            mc.getSoundManager().play(vanilla);
                          }
                        }
                      });
              } finally {
                if (line != null) {
                  if (bass != null) bass.endPlayback();
                  line.close();
                  synchronized (this) {
                    if (directLine == line) directLine = null;
                  }
                }
              }
            });
  }

  private void playMinecraftLoop(
      Minecraft mc, Identifier id, float volume, float pitch, int repetitions) {
    for (int i = 0; i < repetitions; i++) {
      SimpleSoundInstance sound =
          new SimpleSoundInstance(
              id,
              SoundSource.UI,
              volume,
              pitch,
              SoundInstance.createUnseededRandom(),
              true,
              0,
              SoundInstance.Attenuation.NONE,
              0,
              0,
              0,
              true);
      alarmVanilla.add(sound);
      mc.getSoundManager().play(sound);
    }
  }

  /** Small output buffer keeps decoded bass and the sound within about 60 ms of one another. */
  private AudioDevice reactiveDevice(long ticket, float gain, float pitch, BassAnalyzer bass) {
    return new AudioDeviceBase() {
      private SourceDataLine line;
      private AudioFormat format;

      @Override
      protected void writeImpl(short[] samples, int offset, int length) throws JavaLayerException {
        try {
          if (ticket != generation.get()) return;
          if (line == null) {
            format =
                new AudioFormat(
                    getDecoder().getOutputFrequency() * pitch,
                    16,
                    getDecoder().getOutputChannels(),
                    true,
                    false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(
                format, Math.max(1, (int) (format.getSampleRate() * .06)) * format.getFrameSize());
            synchronized (SoundService.this) {
              if (ticket != generation.get()) {
                line.close();
                return;
              }
              directLine = line;
            }
            if (bass != null) bass.awaitScheduledStart(() -> ticket == generation.get());
            if (ticket != generation.get()) return;
            line.start();
            if (bass != null) bass.beginPlayback(() -> line.getMicrosecondPosition() / 1000, pitch);
          }
          int channels = format.getChannels();
          for (int pos = offset; pos < offset + length && ticket == generation.get(); ) {
            int n = Math.min(256 * channels, offset + length - pos);
            byte[] bytes = new byte[n * 2];
            for (int i = 0; i < n; i++) {
              short v = boost(samples[pos + i], gain);
              bytes[i * 2] = (byte) v;
              bytes[i * 2 + 1] = (byte) (v >> 8);
            }
            int written = 0;
            while (written < bytes.length && ticket == generation.get())
              written += line.write(bytes, written, bytes.length - written);
            if (bass != null && ticket == generation.get())
              bass.accept(samples, pos, n, getDecoder().getOutputFrequency(), channels);
            pos += n;
          }
        } catch (Exception e) {
          throw new JavaLayerException("Cannot play reactive audio", e);
        }
      }

      @Override
      protected void flushImpl() {
        if (line != null && ticket == generation.get()) line.drain();
      }

      @Override
      protected void closeImpl() {
        if (line != null) {
          if (bass != null) bass.endPlayback();
          line.close();
          if (directLine == line) directLine = null;
        }
      }

      @Override
      public int getPosition() {
        return line == null ? 0 : (int) (line.getMicrosecondPosition() / 1000);
      }
    };
  }

  private static short[] pcm(ByteBuffer input) {
    short[] output = new short[input.remaining() / 2];
    for (int i = 0; i < output.length; i++)
      output[i] = (short) ((input.get() & 255) | (input.get() << 8));
    return output;
  }

  private static byte[] boost(ByteBuffer input, float gain) {
    byte[] output = new byte[input.remaining()];
    for (int i = 0; i + 1 < output.length; i += 2) {
      short sample = (short) ((input.get() & 0xff) | (input.get() << 8));
      short boosted = boost(sample, gain);
      output[i] = (byte) boosted;
      output[i + 1] = (byte) (boosted >> 8);
    }
    return output;
  }

  private static short boost(short sample, float gain) {
    double value = gain <= 1 ? sample * gain : Math.tanh(sample / 32768.0 * gain) * Short.MAX_VALUE;
    return (short) Math.clamp(Math.round(value), Short.MIN_VALUE, Short.MAX_VALUE);
  }

  @Override
  public void close() {
    stop();
    worker.shutdownNow();
    if (ownsAnalyses) analyses.close();
    if (preview != null) preview.close();
  }
}
