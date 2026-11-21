package dev.asarix.skyblockflow.core;

import java.util.UUID;

/** A display-only avatar. No skin URL or executable action is accepted from a sender. */
public record EditScene(
    String playerId,
    String playerName,
    String dimension,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    float yawSpeed,
    float pitchSpeed) {
  public void validate() {
    UUID.fromString(playerId);
    if (playerName == null
        || !playerName.matches("[A-Za-z0-9_]{1,16}")
        || dimension == null
        || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
        || dimension.length() > 128
        || !Double.isFinite(x)
        || !Double.isFinite(y)
        || !Double.isFinite(z)
        || Math.abs(x) > 29999984
        || Math.abs(z) > 29999984
        || y < -2048
        || y > 2048
        || !Float.isFinite(yaw)
        || Math.abs(yaw) > 360
        || !Float.isFinite(pitch)
        || Math.abs(pitch) > 60
        || !Float.isFinite(yawSpeed)
        || yawSpeed < 0
        || yawSpeed > 180
        || !Float.isFinite(pitchSpeed)
        || pitchSpeed < 0
        || pitchSpeed > 90)
      throw new IllegalArgumentException("Invalid avatar name, position or head speed");
  }

  public EditScene profile(String id, String name) {
    return new EditScene(id, name, dimension, x, y, z, yaw, pitch, yawSpeed, pitchSpeed);
  }

  // A sine wave's mean absolute speed is 2*A*omega/pi. Zero speed holds the initial angle.
  public static float sway(double seconds, float averageSpeed, float amplitude) {
    return averageSpeed == 0
        ? 0
        : (float)
            (amplitude * Math.sin(Math.max(0, seconds) * averageSpeed * Math.PI / (2 * amplitude)));
  }

  public float headYaw(double seconds) {
    return yaw + sway(seconds, yawSpeed, 55);
  }

  public float headPitch(double seconds) {
    return pitch + sway(seconds, pitchSpeed, 20);
  }
}
