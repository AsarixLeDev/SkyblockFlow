package dev.asarix.skyblockflow;

public interface ChatTabAccess {
  enum Tab {
    ALL("All"), CHAT("Chat"), NOTICES("Notices"), MP("mp");

    public final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  static boolean enabled() {
    var app = SkyblockFlowClient.INSTANCE;
    return app != null && app.store != null && app.store.config.chatTabs;
  }

  void skyblockflow$tab(Tab tab);
}
