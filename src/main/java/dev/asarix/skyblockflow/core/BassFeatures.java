package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.Models.CelebrationEditSettings;
import java.util.Arrays;

/** Overlapping Hann windows. Stereo power is combined without cancelling opposite phases. */
public final class BassFeatures {
  private final CelebrationEditSettings settings;
  private int rate, channels, size, hop, write, untilNext, bandBins;
  private long frames;
  private long originalDuration = -1;
  private boolean padding;

  public void finish() {
    if (originalDuration >= 0 || rate == 0) return;
    long duration = durationMs();
    int padding = size / 2 + Math.max(1, rate * 3 / 20);
    this.padding = true;
    accept(new short[padding * channels], 0, padding * channels, rate, channels);
    originalDuration = duration;
  }

  private double[][] ring;
  private double[] re, im, hann, previous;
  private float[] energy = new float[1024], flux = new float[1024];
  private boolean[] eligible = new boolean[1024];
  private int count;
  private float peak;
  private double[] background;

  boolean matches(int r, int c) {
    return rate == r && channels == c;
  }

  float[] rawEnergy() {
    return energy;
  }

  float[] rawFlux() {
    return flux;
  }

  public BassFeatures(CelebrationEditSettings settings) {
    this.settings = settings;
  }

  public int count() {
    return count;
  }

  public boolean narrowBand() {
    return bandBins <= 2;
  }

  boolean eligible(int frame) {
    return eligible[frame];
  }

  public float energy(int i) {
    return energy[Math.clamp(i, 0, count - 1)];
  }

  public float flux(int i) {
    return flux[Math.clamp(i, 0, count - 1)];
  }

  public float peak() {
    return peak;
  }

  public double stepMs() {
    return rate == 0 ? 1000d / settings.analysisHz : hop * 1000d / rate;
  }

  public long time(int i) {
    return Math.max(0, Math.round(((i + 1d) * hop - size / 2d) * 1000 / rate));
  }

  public long durationMs() {
    return originalDuration >= 0
        ? originalDuration
        : rate == 0 ? 0 : Math.round(frames * 1000d / rate);
  }

  public float[] energies() {
    return Arrays.copyOf(energy, count);
  }

  public float[] fluxes() {
    return Arrays.copyOf(flux, count);
  }

  public long originMs() {
    return rate == 0 ? 0 : Math.round((hop - size / 2d) * 1000 / rate);
  }

  public void accept(short[] pcm, int offset, int length, int sampleRate, int channelCount) {
    if (sampleRate <= 0 || channelCount < 1 || channelCount > 8) return;
    if (rate != sampleRate || channels != channelCount) {
      rate = sampleRate;
      channels = channelCount;
      size = 1;
      while (size < rate * .046) size <<= 1;
      if (size > 2 && Math.abs(size / 2d - rate * .046) < Math.abs(size - rate * .046)) size /= 2;
      hop = Math.max(1, rate / settings.analysisHz);
      untilNext = hop;
      write = count = 0;
      frames = 0;
      peak = 0;

      ring = new double[channels][size];
      re = new double[size];
      im = new double[size];
      hann = new double[size];
      previous = new double[size / 2 + 1];
      background = new double[size / 2 + 1];
      for (int i = 0; i < size; i++) hann[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (size - 1));
    }
    for (int i = offset; i + channels <= offset + length; i += channels) {
      for (int ch = 0; ch < channels; ch++) ring[ch][write] = pcm[i + ch] / 32768d;
      write = (write + 1) % size;
      frames++;
      if (--untilNext == 0) {
        analyze();
        untilNext = hop;
      }
    }
  }

  private void analyze() {
    double[] power = new double[size / 2 + 1];
    int first = Math.max(1, (int) Math.ceil(settings.bassLowHz * size / rate - .5));
    int last = Math.min(size / 2, (int) Math.floor(settings.bassHighHz * size / rate + .5));
    bandBins = Math.max(0, last - first + 1);
    for (int ch = 0; ch < channels; ch++) {
      for (int i = 0; i < size; i++) {
        re[i] = ring[ch][(write + i) % size] * hann[i];
        im[i] = 0;
      }
      fft(re, im);
      for (int b = 1; b <= size / 2; b++) power[b] += (re[b] * re[b] + im[b] * im[b]) / channels;
    }
    double total = 0, bandPower = 0, bandMax = 0;
    double[] magnitude = new double[power.length];
    for (int b = 1; b < power.length; b++) {
      total += power[b];
      magnitude[b] = Math.sqrt(power[b]) / size;
      if (b >= first && b <= last) {
        bandPower += power[b];
        bandMax = Math.max(bandMax, magnitude[b]);
      }
    }
    double level = Math.sqrt(bandPower) / size;
    double rise = novelty(magnitude, first, last, bandMax);
    if (settings.bassDetectionMode == BassDetectionMode.KICK) {
      int attackFirst = Math.max(last + 1, (int) (180d * size / rate));
      int attackLast = Math.min(power.length - 2, (int) (1500d * size / rate));
      double attackMax = 0;
      for (int b = attackFirst; b <= attackLast; b++) attackMax = Math.max(attackMax, magnitude[b]);
      // A simultaneous upper attack supports a kick; treble alone never creates a bass hit.
      double attack = novelty(magnitude, attackFirst, attackLast, attackMax);
      rise *= .85 + .3 * Math.min(1, attack);
    }
    // Absolute silence and spectral leakage guards, independent of the song's loudest passage.
    if (level < .000001 || bandPower < total * .005 || count > 0 && level <= energy[count - 1])
      rise = 0;
    double decay = Math.exp(-hop / (double) rate / .7);
    for (int b = 1; b < power.length; b++) {
      background[b] = Math.max(magnitude[b], background[b] * decay);
      previous[b] = magnitude[b];
    }
    if (count == energy.length) {
      energy = Arrays.copyOf(energy, count * 2);
      flux = Arrays.copyOf(flux, count * 2);
      eligible = Arrays.copyOf(eligible, count * 2);
    }
    if (padding) rise = 0;
    energy[count] = (float) level;
    flux[count] = (float) rise;
    eligible[count] = !padding && level >= .000001 && bandPower >= total * .005;
    peak = Math.max(peak, energy[count]);
    count++;
  }

  /** Compressed, locally whitened spectral growth with pitch-slide suppression. */
  private double novelty(double[] magnitude, int first, int last, double maximum) {
    double sum = 0, growth = 0, loss = 0;
    int bins = 0;
    for (int b = Math.max(1, first); b <= Math.min(last, magnitude.length - 2); b++) {
      double change = magnitude[b] * magnitude[b] - previous[b] * previous[b];
      growth += Math.max(0, change);
      loss += Math.max(0, -change);
      if (magnitude[b] < Math.max(.0000002, maximum * .04)) continue;
      // Compare against neighboring previous frequencies, so a moving note is not a fresh attack.
      double reference = Math.max(previous[b], Math.max(previous[b - 1], previous[b + 1]));
      double scale = Math.max(.0001, Math.max(background[b], maximum * .1));
      sum +=
          Math.max(0, Math.log1p(10 * magnitude[b] / scale) - Math.log1p(10 * reference / scale));
      bins++;
    }
    // Suppress a note ending: an abrupt cutoff spreads a little energy into side bins.
    // Equal growth and loss (a new kick over a decaying note) remains fully eligible.
    return bins == 0 ? 0 : sum / bins / Math.log(11) * Math.min(1, 3 * growth / (loss + 1e-12));
  }

  private static void fft(double[] re, double[] im) {
    int n = re.length;
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        double t = re[i];
        re[i] = re[j];
        re[j] = t;
      }
    }
    for (int len = 2; len <= n; len <<= 1) {
      double angle = -2 * Math.PI / len, wr0 = Math.cos(angle), wi0 = Math.sin(angle);
      for (int i = 0; i < n; i += len) {
        double wr = 1, wi = 0;
        for (int j = 0; j < len / 2; j++) {
          int a = i + j, b = a + len / 2;
          double vr = re[b] * wr - im[b] * wi, vi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - vr;
          im[b] = im[a] - vi;
          re[a] += vr;
          im[a] += vi;
          double next = wr * wr0 - wi * wi0;
          wi = wr * wi0 + wi * wr0;
          wr = next;
        }
      }
    }
  }
}
