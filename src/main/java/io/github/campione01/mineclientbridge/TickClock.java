package io.github.campione01.mineclientbridge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Counts finished client ticks, so an input request can wait until the game has processed it.
 *
 * <p>Bridge work runs from Minecraft's task queue, which the client drains before it ticks. A key
 * press therefore lands as a pending click that the next tick's key handling consumes. An agent
 * that sends several inputs between two ticks has them all consumed by one tick, which is not what a
 * person pressing keys produces. Waiting for {@code settle_ticks} finished ticks after each input
 * gives every input its own tick, without guessing a delay in milliseconds.</p>
 *
 * <p>The clock counts {@link ClientTickEvent.Post}, which the client fires every tick, paused or
 * not, in a world or on the title screen. Waiting happens on the HTTP worker thread, never on the
 * Minecraft thread.</p>
 */
public final class TickClock {
    public static final int MAX_SETTLE_TICKS = 40;

    private static final AtomicLong TICKS = new AtomicLong();
    private static final ConcurrentLinkedQueue<Waiter> WAITERS = new ConcurrentLinkedQueue<>();
    private static volatile boolean installed;

    private TickClock() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ClientTickEvent.Post.class,
                event -> onTick());
        installed = true;
    }

    public static boolean installed() {
        return installed;
    }

    public static long now() {
        return TICKS.get();
    }

    /** Called once per finished client tick. Package-private so tests can drive the clock. */
    static void onTick() {
        long now = TICKS.incrementAndGet();
        WAITERS.removeIf(waiter -> {
            if (now >= waiter.target()) {
                waiter.future().complete(now);
                return true;
            }
            return false;
        });
    }

    /**
     * Blocks the calling thread until {@code ticks} more client ticks have finished, and returns the
     * tick count at that point. Zero returns at once.
     */
    public static long awaitTicks(int ticks, long timeoutMillis)
            throws InterruptedException, TimeoutException {
        if (ticks < 0 || ticks > MAX_SETTLE_TICKS) {
            throw new IllegalArgumentException("ticks must be between 0 and " + MAX_SETTLE_TICKS);
        }
        long start = TICKS.get();
        if (ticks == 0) {
            return start;
        }
        Waiter waiter = new Waiter(start + ticks, new CompletableFuture<>());
        WAITERS.add(waiter);
        // A tick may have finished between reading the count and queueing the waiter.
        if (TICKS.get() >= waiter.target()) {
            WAITERS.remove(waiter);
            waiter.future().complete(TICKS.get());
        }
        try {
            return waiter.future().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Tick wait failed", e.getCause());
        } catch (TimeoutException e) {
            WAITERS.remove(waiter);
            throw e;
        }
    }

    private record Waiter(long target, CompletableFuture<Long> future) {
    }
}
