package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class TickClockTest {
    @Test
    void zeroTicksReturnsAtOnce() throws Exception {
        assertEquals(TickClock.now(), TickClock.awaitTicks(0, 10));
    }

    @Test
    void waitEndsAfterTheRequestedNumberOfTicks() throws Exception {
        long start = TickClock.now();
        CompletableFuture<Long> waited = CompletableFuture.supplyAsync(() -> {
            try {
                return TickClock.awaitTicks(2, 5_000);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // Let the waiter queue itself before the clock moves.
        Thread.sleep(100);
        TickClock.onTick();
        Thread.sleep(50);
        assertFalse(waited.isDone(), "one tick must not end a two-tick wait");
        TickClock.onTick();
        assertTrue(waited.get(2, TimeUnit.SECONDS) >= start + 2);
    }

    @Test
    void waitTimesOutWhenTheClientStopsTicking() {
        assertThrows(TimeoutException.class, () -> TickClock.awaitTicks(1, 50));
    }

    @Test
    void rejectsOutOfRangeTicks() {
        assertThrows(IllegalArgumentException.class, () -> TickClock.awaitTicks(-1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> TickClock.awaitTicks(TickClock.MAX_SETTLE_TICKS + 1, 10));
    }
}
