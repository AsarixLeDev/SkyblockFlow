package dev.asarix.skyblockflow.core;

import dev.asarix.skyblockflow.core.ExcaliaApiData.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, nonblocking quote lookup for tooltips. Inputs are immutable snapshots. */
public final class MarketQuotes implements AutoCloseable {
  public record Quotes(List<Offer> sellers, List<Offer> buyers) {
    public List<String> lines(int count) {
      var lines = new ArrayList<String>();
      ExcaliaApiData.quote(lines, sellers, count, false);
      ExcaliaApiData.quote(lines, buyers, count, true);
      return List.copyOf(lines);
    }
  }
  private record Key(String item, String name) {}
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>(), task -> {
        var thread = new Thread(task, "Flow market quotes");
        thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
      });
  private final LinkedHashMap<Key, Future<Quotes>> cache = new LinkedHashMap<>(32, .75f, true);
  private Market market;
  private long revision;

  public Quotes get(Market snapshot, Map<String, Long> stock, long stockRevision, String item, String name) {
    if (market != snapshot || revision != stockRevision) {
      clear(); market = snapshot; revision = stockRevision;
    }
    if (snapshot == null || worker.isShutdown()) return null;
    var key = new Key(item, name);
    var result = cache.get(key);
    if (result == null) {
      if (cache.size() >= 256) cache.pollFirstEntry().getValue().cancel(true);
      // Moving across many items never builds an unbounded work queue.
      if (worker.getQueue().size() >= 16) return null;
      result = worker.submit(() -> select(snapshot, stock, item, name));
      cache.put(key, result);
      worker.purge();
    }
    return result.state() == Future.State.SUCCESS ? result.resultNow() : null;
  }

  public static Quotes select(Market market, Map<String, Long> stock, String item, String name) {
    var selling = new ArrayList<Offer>();
    for (var offer : market.selling().getOrDefault(item, List.of())) {
      if (Thread.currentThread().isInterrupted()) throw new CancellationException();
      var remaining = stock.get(offer.identity());
      selling.add(remaining == null || offer.unlimited() ? offer : offer.withStock(Math.min(remaining, offer.stock())));
    }
    return new Quotes(ExcaliaApiData.best(selling, name, false),
        ExcaliaApiData.best(market.buying().getOrDefault(item, List.of()), name, true));
  }

  public void clear() {
    cache.values().forEach(job -> job.cancel(true)); cache.clear(); worker.purge(); market = null;
  }
  @Override public void close() { clear(); worker.shutdownNow(); }
}
