package dev.asarix.skyblockflow;

public interface ChatTabAccess {
  enum Tab {
    ALL("All"), CHAT("Chat"), NOTICES("Notices");

    public final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  void skyblockflow$tab(Tab tab);
}
