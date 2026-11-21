package dev.asarix.skyblockflow.core;

public enum BassDetectionMode {
  ALL_BASS("All bass attacks"),
  KICK("Kick / drum hits");
  public final String label;

  BassDetectionMode(String label) {
    this.label = label;
  }
}
