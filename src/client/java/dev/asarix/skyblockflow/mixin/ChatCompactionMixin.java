package dev.asarix.skyblockflow.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.ChatTabAccess;
import dev.asarix.skyblockflow.core.ChatNameHeader;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import dev.asarix.skyblockflow.core.RecentChatGroups;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wrapped lines are grouped; original messages, signatures and deletion history remain intact. */
@Mixin(ChatComponent.class)
public abstract class ChatCompactionMixin implements ChatTabAccess {
  @Shadow @Final private Minecraft minecraft;
  @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
  @Shadow @Final private List<GuiMessage> allMessages;
  @Shadow private Predicate<GuiMessage> visibleMessageFilter;
  @Shadow private int chatScrollbarPos;
  @Shadow private boolean newMessageSinceScroll;
  @Shadow public abstract int getLinesPerPage();
  @Invoker("refreshTrimmedMessages") protected abstract void skyblockflow$rewrap();
  @Unique private final Deque<GuiMessage> skyblockflow$evicted = new ArrayDeque<>();
  @Unique private final Map<GuiMessage, Long> skyblockflow$received = new IdentityHashMap<>();
  @Unique private Map<GuiMessage, Integer> skyblockflow$groups = Map.of();
  @Unique private boolean skyblockflow$rebuilding;
  @Unique private Tab skyblockflow$tab = Tab.ALL;
  @Unique private final dev.asarix.skyblockflow.core.PrivateMessages skyblockflow$privatePatterns = new dev.asarix.skyblockflow.core.PrivateMessages();
  @Unique private GuiMessage.Line skyblockflow$anchor;
  @Unique private int skyblockflow$anchorOffset;
  @Unique private boolean skyblockflow$arrivalRewrap;
  @Unique private final Map<GuiMessage, Wrapped> skyblockflow$wrapped = new IdentityHashMap<>();
  @Unique private final Map<GuiMessage, Byte> skyblockflow$channels = new IdentityHashMap<>();
  private record Wrapped(Font font, int width, int count, List<FormattedCharSequence> lines) {}
  @Unique private GuiMessage skyblockflow$removed;
  @Unique private final LinkedHashMap<List<Object>, Long> skyblockflow$latest = new LinkedHashMap<>();

  @Override
  public void skyblockflow$tab(Tab tab) {
    if (!ChatTabAccess.enabled()) tab = Tab.ALL;
    if (tab == skyblockflow$tab) return;
    skyblockflow$tab = tab;
    skyblockflow$channels.clear();
    chatScrollbarPos = 0;
    newMessageSinceScroll = false;
    skyblockflow$rewrap();
  }

  @Unique private boolean skyblockflow$matchesTab(GuiMessage message) {
    if (!ChatTabAccess.enabled() || skyblockflow$tab == Tab.ALL) return true;
    byte channel = skyblockflow$channels.computeIfAbsent(message, this::skyblockflow$classify);
    if (skyblockflow$tab == Tab.MP) return channel == 3;
    return channel == 2 || channel == 3 || (channel == 1) == (skyblockflow$tab == Tab.CHAT);
  }

  @Unique private byte skyblockflow$classify(GuiMessage message) {
    String plain = message.content().getString();
    var app = SkyblockFlowClient.INSTANCE;
    if (app != null && skyblockflow$privatePatterns.matches(plain, app.store.config.alerts)) return 3;
    if (message.source() == GuiMessageSource.PLAYER) return 1;
    if (skyblockflow$privateMessage(plain)) return 2;
    var sender = ChatNameHeader.find(plain);
    return (byte) (sender != null && minecraft.getConnection() != null
        && minecraft.getConnection().getPlayerInfo(sender.name()) != null ? 1 : 0);
  }

  @Unique private static boolean skyblockflow$privateMessage(String text) {
    String n = dev.asarix.skyblockflow.core.JobXp.normalize(text);
    return n.startsWith("[staff]") || n.startsWith("[moderation]")
        || n.startsWith("[mp]") || n.startsWith("[pm]") || n.startsWith("[msg]")
        || n.contains(" whispers to you:") || n.contains(" vous chuchote")
        || n.startsWith("de ") && n.contains(" → ") && n.contains("moi")
        || n.startsWith("moi → ");
  }

  @WrapOperation(method = "refreshTrimmedMessages", at = @At(value = "INVOKE",
      target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"))
  private boolean skyblockflow$tabFilter(Predicate<GuiMessage> filter, Object message,
      Operation<Boolean> original) {
    return original.call(filter, message) && skyblockflow$matchesTab((GuiMessage) message);
  }

  @Unique private List<Object> skyblockflow$key(GuiMessage message) {
    return Arrays.asList(message.content(), message.source(), message.tag());
  }


  @Unique private static boolean skyblockflow$enabled() {
    var app = SkyblockFlowClient.INSTANCE;
    return app != null && app.store != null && app.store.config.compactChat;
  }
  @Unique private long skyblockflow$time(GuiMessage message) {
    return skyblockflow$received.computeIfAbsent(message, ignored -> System.nanoTime() / 1_000_000L
        - Math.max(0L, (long)minecraft.gui.hud.getGuiTicks() - message.addedTime()) * 50);
  }
  @Unique private void skyblockflow$prune() {
    long now = System.nanoTime() / 1_000_000L;
    while (!skyblockflow$evicted.isEmpty() && (skyblockflow$evicted.size() > 10_000
        || now - skyblockflow$time(skyblockflow$evicted.getLast()) > RecentChatGroups.WINDOW_MS)) skyblockflow$evicted.removeLast();
    Set<GuiMessage> keep = Collections.newSetFromMap(new IdentityHashMap<>());
    keep.addAll(allMessages); keep.addAll(skyblockflow$evicted);
    skyblockflow$received.keySet().retainAll(keep);
    skyblockflow$channels.keySet().retainAll(keep);
  }

  @Inject(method = "addMessageToQueue", at = @At("HEAD"))
  private void skyblockflow$receive(GuiMessage message, CallbackInfo ci) {
    if (ChatTabAccess.enabled()) skyblockflow$channels.computeIfAbsent(message, this::skyblockflow$classify);
    skyblockflow$removed = allMessages.size() >= 100 ? allMessages.getLast() : null;
    if (!skyblockflow$enabled()) return;
    long now = System.nanoTime() / 1_000_000L;
    skyblockflow$received.put(message, now);
    if (skyblockflow$received.size() > 10200) skyblockflow$prune();
    if (skyblockflow$removed != null) skyblockflow$evicted.addFirst(skyblockflow$removed);
  }
  @Inject(method = "addMessage", at = @At("HEAD"))
  private void skyblockflow$rememberScroll(Component contents, net.minecraft.network.chat.MessageSignature signature,
      GuiMessageSource source, net.minecraft.client.multiplayer.chat.GuiMessageTag tag, CallbackInfo ci) {
    skyblockflow$anchor = chatScrollbarPos > 0 && chatScrollbarPos < trimmedMessages.size()
        ? trimmedMessages.get(chatScrollbarPos) : null;
    skyblockflow$anchorOffset = 0;
    if (skyblockflow$anchor != null)
      for (int i = chatScrollbarPos - 1; i >= 0 && trimmedMessages.get(i).parent() == skyblockflow$anchor.parent(); i--)
        skyblockflow$anchorOffset++;
  }

  @Inject(method = "addMessageToQueue", at = @At("TAIL"))
  private void skyblockflow$compact(GuiMessage message, CallbackInfo ci) {
    if (skyblockflow$tab != Tab.ALL && skyblockflow$removed != null
        && trimmedMessages.removeIf(line -> line.parent() == skyblockflow$removed))
      chatScrollbarPos = Math.min(chatScrollbarPos, Math.max(0, trimmedMessages.size() - getLinesPerPage()));
    if (skyblockflow$removed != null) {
      skyblockflow$wrapped.remove(skyblockflow$removed);
      if (!skyblockflow$enabled()) skyblockflow$channels.remove(skyblockflow$removed);
    }
    skyblockflow$removed = null;
    if (!skyblockflow$enabled()) return;
    long now = skyblockflow$time(message);
    while (!skyblockflow$latest.isEmpty() && (skyblockflow$latest.size() >= 10000
        || now - skyblockflow$latest.firstEntry().getValue() > RecentChatGroups.WINDOW_MS))
      skyblockflow$latest.pollFirstEntry();
    var key = skyblockflow$key(message);
    Long previous = skyblockflow$latest.remove(key);
    skyblockflow$latest.putLast(key, now);
    if (!skyblockflow$matchesTab(message) || previous == null || now - previous > RecentChatGroups.WINDOW_MS) return;
    int scroll = chatScrollbarPos;
    boolean unread = newMessageSinceScroll;
    chatScrollbarPos = 0;
    skyblockflow$arrivalRewrap = true;
    try {
      skyblockflow$rewrap();
    } finally {
      skyblockflow$arrivalRewrap = false;
    }
    if (skyblockflow$anchor != null) {
      var origin = skyblockflow$anchor.parent();
      var group = skyblockflow$key(origin);
      for (int i = 0; i < trimmedMessages.size(); i++) {
        var line = trimmedMessages.get(i);
        if (line.parent() == origin || line.endOfEntry() && skyblockflow$key(line.parent()).equals(group)) {
          int offset = 0;
          while (offset < skyblockflow$anchorOffset && i + offset + 1 < trimmedMessages.size()
              && trimmedMessages.get(i + offset + 1).parent() == line.parent()) offset++;
          scroll = i + offset;
          if (line.parent() == origin) break;
        }
      }
    }
    chatScrollbarPos = Math.min(scroll, Math.max(0, trimmedMessages.size() - getLinesPerPage()));
    newMessageSinceScroll = unread;
  }
  @Inject(method = "clearMessages", at = @At("TAIL"))
  private void skyblockflow$clear(boolean history, CallbackInfo ci) { skyblockflow$reset(); }
  @Inject(method = "restoreState", at = @At("HEAD"))
  private void skyblockflow$restore(ChatComponent.State state, CallbackInfo ci) { skyblockflow$reset(); }
  @Unique private void skyblockflow$reset() {
    skyblockflow$evicted.clear(); skyblockflow$received.clear(); skyblockflow$latest.clear(); skyblockflow$wrapped.clear(); skyblockflow$channels.clear(); skyblockflow$anchor = null; skyblockflow$groups = Map.of(); skyblockflow$rebuilding = false;
  }

  @Inject(method = "refreshTrimmedMessages", at = @At("HEAD"))
  private void skyblockflow$plan(CallbackInfo ci) {
    if (!skyblockflow$arrivalRewrap) skyblockflow$wrapped.clear();
    skyblockflow$prune();
    skyblockflow$rebuilding = true;
    skyblockflow$groups = skyblockflow$enabled()
        ? RecentChatGroups.counts(allMessages.stream().filter(m -> visibleMessageFilter.test(m) && skyblockflow$matchesTab(m)).toList(),
            skyblockflow$evicted.stream().filter(m -> visibleMessageFilter.test(m) && skyblockflow$matchesTab(m)).toList(),
            message -> Arrays.asList(message.content(), message.source(), message.tag()), this::skyblockflow$time)
        : Map.of();
  }
  @Inject(method = "refreshTrimmedMessages", at = @At("TAIL"))
  private void skyblockflow$planned(CallbackInfo ci) { skyblockflow$rebuilding = false; }
  @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
  private void skyblockflow$skipOlder(GuiMessage message, CallbackInfo ci) {
    if (!skyblockflow$matchesTab(message) || skyblockflow$rebuilding && skyblockflow$groups.getOrDefault(message, 1) == 0) ci.cancel();
  }

  @WrapOperation(method = "addMessageToDisplayQueue", at = @At(value = "INVOKE", target =
      "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
  private List<FormattedCharSequence> skyblockflow$group(GuiMessage message, Font font, int width,
      Operation<List<FormattedCharSequence>> original) {
    int count = skyblockflow$rebuilding ? skyblockflow$groups.getOrDefault(message, 1) : 1;
    if (!skyblockflow$enabled()) return original.call(message, font, width);
    var cached = skyblockflow$wrapped.get(message);
    if (cached != null && cached.font() == font && cached.width() == width && cached.count() == count)
      return cached.lines();
    var rendered = message;
    if (count > 1) {
      var display = Component.empty().append(message.content()).append(Component.literal(" (x"
        + dev.asarix.skyblockflow.core.Numbers.integer(count) + ")").withStyle(ChatFormatting.GRAY));
      rendered = new GuiMessage(message.addedTime(), display, message.signature(), message.source(), message.tag());
    }
    var lines = original.call(rendered, font, width);
    skyblockflow$wrapped.put(message, new Wrapped(font, width, count, lines));
    return lines;
  }
}
