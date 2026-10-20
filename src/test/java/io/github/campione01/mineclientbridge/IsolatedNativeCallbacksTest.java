package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.system.Callback;

class IsolatedNativeCallbacksTest {
    private static final long WINDOW = 41;

    private FakeRegistrar registrar;
    private IsolatedNativeCallbacks.Registry registry;
    private final Set<Long> windows = new LinkedHashSet<>();
    private final List<ForeignCallback> foreignCallbacks = new ArrayList<>();
    private String originalEnabled;

    @BeforeEach
    void prepareWithoutGlfwWindows() {
        registrar = new FakeRegistrar();
        registry = new IsolatedNativeCallbacks.Registry(registrar);
        originalEnabled = System.getProperty("mineclientBridge.isolatedInput");
        System.setProperty("mineclientBridge.isolatedInput", "true");
        ClientInputIsolation.releaseAllKeys();
    }

    @AfterEach
    void freeNativeClosuresAndRestoreProperty() {
        try {
            registrar.clearFailures();
            for (long window : windows) {
                prepareAndVanillaClose(window);
                registry.release(window);
            }
            registrar.created.forEach(IsolatedNativeCallbacks.OwnedCallback::free);
            for (ForeignCallback foreign : foreignCallbacks) {
                if (!foreign.state.freed.get() && !registrar.staticallyFreed.contains(foreign.callback)) {
                    foreign.callback.free();
                }
            }
        } finally {
            ClientInputIsolation.releaseAllKeys();
            if (originalEnabled == null) System.clearProperty("mineclientBridge.isolatedInput");
            else System.setProperty("mineclientBridge.isolatedInput", originalEnabled);
        }
    }

    @Test
    void allSixCallbacksAreUnconditionalNoOpsIncludingInvalidDropAddresses() {
        ensureAll(WINDOW);
        ClientInputIsolation.setClipboard("untouched");
        var before = ClientInputIsolation.statistics();

        assertDoesNotThrow(() -> invokeAll(WINDOW));
        ClientInputIsolation.syntheticDispatch(() -> {
            assertTrue(ClientInputIsolation.isSyntheticDispatch());
            assertDoesNotThrow(() -> invokeAll(WINDOW));
            ClientInputIsolation.syntheticDispatch(() -> invokeAll(WINDOW));
        });
        System.setProperty("mineclientBridge.isolatedInput", "false");
        assertDoesNotThrow(() -> invokeAll(WINDOW));

        var after = ClientInputIsolation.statistics();
        assertEquals(before.syntheticInputCallbacks(), after.syntheticInputCallbacks());
        assertEquals(before.suppressedNativeCallbacks(), after.suppressedNativeCallbacks());
        assertEquals(0, after.heldKeys());
        assertEquals("untouched", ClientInputIsolation.getClipboard());
        assertEquals(6, registrar.created.size());
    }

    @Test
    void keyboardAndMouseGroupsAreInstalledOnceWithoutCallbackAllocationGrowth() {
        windows.add(WINDOW);
        registry.ensureKeyboard(WINDOW);
        assertEquals(2, registrar.created.size());
        assertEquals(2, registrar.setAttempts);
        assertNull(registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.DROP));
        registry.ensureMouse(WINDOW);
        for (int i = 0; i < 1_000; i++) {
            registry.ensureKeyboard(WINDOW);
            registry.ensureMouse(WINDOW);
        }
        assertEquals(6, registrar.allocateAttempts);
        assertEquals(6, registrar.created.size());
        assertEquals(6, registrar.setAttempts);
        for (var slot : IsolatedNativeCallbacks.Slot.values()) {
            assertNotNull(registrar.current(WINDOW, slot));
        }
    }

    @Test
    void mouseCanBePreparedBeforeKeyboard() {
        windows.add(WINDOW);
        registry.ensureMouse(WINDOW);
        assertEquals(4, registrar.created.size());
        registry.ensureKeyboard(WINDOW);
        assertEquals(6, registrar.created.size());
        assertEquals(6, registrar.setAttempts);
    }

    @Test
    void allSixExistingCallbacksAreRestoredAndUnusedPlaceholdersFreed() {
        for (var slot : IsolatedNativeCallbacks.Slot.values()) {
            ForeignCallback foreign = foreign(slot, null);
            registrar.put(WINDOW, slot, foreign.callback);
        }
        ensureAll(WINDOW);
        assertEquals(12, registrar.setAttempts);
        assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        for (int i = 0; i < IsolatedNativeCallbacks.Slot.values().length; i++) {
            var slot = IsolatedNativeCallbacks.Slot.values()[i];
            ForeignCallback foreign = foreignCallbacks.get(i);
            assertSame(foreign.callback, registrar.current(WINDOW, slot));
            assertEquals(0, foreign.state.freeCalls);
        }

        invokeAll(WINDOW);
        assertTrue(foreignCallbacks.stream().allMatch(callback -> callback.state.calls == 1));
        int setsBeforeClose = registrar.setAttempts;
        registry.prepareClose(WINDOW);
        assertEquals(setsBeforeClose, registrar.setAttempts);
        registrar.vanillaClose(WINDOW);
        registry.release(WINDOW);
        assertTrue(foreignCallbacks.stream().allMatch(callback -> callback.state.freeCalls == 0));
        assertEquals(6, registrar.staticFreeCalls);
    }

    @Test
    void lwjglPreviousCallbackWrappersKeepOwnedIdentityAndFreeIsIdempotent() {
        ensureAll(WINDOW);
        for (var slot : IsolatedNativeCallbacks.Slot.values()) {
            Callback owned = registrar.current(WINDOW, slot);
            assertSame(owned, wrapperAt(slot, owned.address()));
        }
        prepareAndVanillaClose(WINDOW);
        assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        assertEquals(0, registrar.staticFreeCalls);
        int setsBeforeRelease = registrar.setAttempts;
        registry.release(WINDOW);
        registry.release(WINDOW);
        registrar.created.forEach(IsolatedNativeCallbacks.OwnedCallback::free);
        assertEquals(setsBeforeRelease, registrar.setAttempts);
    }

    @Test
    void foreignCurrentChainsLeaveDetachedPredecessorsForOwnedRelease() {
        ensureAll(WINDOW);
        for (var slot : IsolatedNativeCallbacks.Slot.values()) {
            Callback previous = registrar.current(WINDOW, slot);
            registrar.put(WINDOW, slot, foreign(slot, previous).callback);
        }
        ClientInputIsolation.syntheticDispatch(() -> invokeAll(WINDOW));
        assertEquals(0, ClientInputIsolation.statistics().heldKeys());

        prepareAndVanillaClose(WINDOW);
        assertTrue(foreignCallbacks.stream().allMatch(callback -> registrar.staticallyFreed.contains(callback.callback)));
        assertEquals(6, registrar.staticFreeCalls);
        assertTrue(foreignCallbacks.stream().allMatch(callback -> callback.state.freeCalls == 0));
        assertTrue(registrar.created.stream().noneMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        registry.release(WINDOW);
        registry.release(WINDOW);
        assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        assertTrue(foreignCallbacks.stream().allMatch(callback -> callback.state.freeCalls == 0));
    }

    @Test
    void mixedCurrentSlotsFreeOwnedAtHeadAndForeignStaticallyBeforeTail() {
        ensureAll(WINDOW);
        Set<IsolatedNativeCallbacks.Slot> foreignSlots = Set.of(
                IsolatedNativeCallbacks.Slot.KEY, IsolatedNativeCallbacks.Slot.SCROLL,
                IsolatedNativeCallbacks.Slot.DROP);
        for (var slot : foreignSlots) {
            registrar.put(WINDOW, slot, foreign(slot, registrar.current(WINDOW, slot)).callback);
        }
        registry.prepareClose(WINDOW);
        for (var slot : IsolatedNativeCallbacks.Slot.values()) {
            assertEquals(!foreignSlots.contains(slot), registrar.created.get(slot.ordinal()).isFreed());
            if (foreignSlots.contains(slot)) assertNotNull(registrar.current(WINDOW, slot));
            else assertNull(registrar.current(WINDOW, slot));
        }
        registrar.vanillaClose(WINDOW);
        assertEquals(3, registrar.staticFreeCalls);
        registry.release(WINDOW);
        assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        assertTrue(foreignCallbacks.stream().allMatch(callback -> callback.state.freeCalls == 0));
    }

    @Test
    void releaseBeforePreparedCloseRejectsWithoutRemovingContextOrFreeingAnything() {
        windows.add(WINDOW);
        registry.ensureKeyboard(WINDOW);
        Callback key = registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.KEY);
        assertThrows(IllegalStateException.class, () -> registry.release(WINDOW));
        assertSame(key, registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.KEY));
        assertTrue(registrar.created.stream().noneMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        registry.ensureMouse(WINDOW);
        assertEquals(6, registrar.created.size());
        prepareAndVanillaClose(WINDOW);
        registry.release(WINDOW);
    }

    @Test
    void preparedCloseIsOneShotAndFurtherRegistrationFailsBeforeAllocation() {
        ensureAll(WINDOW);
        registry.prepareClose(WINDOW);
        int setsAfterHead = registrar.setAttempts;
        registry.prepareClose(WINDOW);
        registry.prepareClose(WINDOW);
        assertEquals(setsAfterHead, registrar.setAttempts);
        assertThrows(IllegalStateException.class, () -> registry.ensureKeyboard(WINDOW));
        assertThrows(IllegalStateException.class, () -> registry.ensureMouse(WINDOW));
        assertEquals(6, registrar.allocateAttempts);
        assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        registrar.vanillaClose(WINDOW);
        registry.release(WINDOW);
    }

    @Test
    void closeDuringRegistrationRejectsBeforeAnyClosingMutationOrExtraSetter() {
        windows.add(WINDOW);
        registrar.duringSet = () -> {
            int setsBeforeClose = registrar.setAttempts;
            assertThrows(IllegalStateException.class, () -> registry.prepareClose(WINDOW));
            assertEquals(setsBeforeClose, registrar.setAttempts);
            registrar.duringSet = null;
        };
        registry.ensureKeyboard(WINDOW);
        registry.ensureMouse(WINDOW);
        assertEquals(6, registrar.created.size());
        assertEquals(6, registrar.setAttempts);
    }

    @Test
    void unmappedCloseAndReleaseDoNotQueryOrChangeNativeSlots() {
        registry.prepareClose(99);
        registry.release(99);
        assertEquals(0, registrar.setAttempts);
        assertEquals(0, registrar.allocateAttempts);
    }

    @Test
    void repeatedPreparationDoesNotClobberLaterForeignChains() {
        ensureAll(WINDOW);
        ForeignCallback foreign = foreign(IsolatedNativeCallbacks.Slot.KEY,
                registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.KEY));
        registrar.put(WINDOW, IsolatedNativeCallbacks.Slot.KEY, foreign.callback);
        ensureAll(WINDOW);
        assertSame(foreign.callback, registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.KEY));
        assertEquals(6, registrar.created.size());
        assertEquals(6, registrar.setAttempts);
        assertEquals(0, foreign.state.freeCalls);
    }

    @Test
    void releasedHandleCanBeReusedWithFreshGroupsAndOwnership() {
        ensureAll(WINDOW);
        Callback oldKey = registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.KEY);
        prepareAndVanillaClose(WINDOW);
        registry.release(WINDOW);
        ensureAll(WINDOW);
        Callback newKey = registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.KEY);
        assertNotSame(oldKey, newKey);
        assertTrue(((IsolatedNativeCallbacks.OwnedCallback) oldKey).isFreed());
        assertFalse(((IsolatedNativeCallbacks.OwnedCallback) newKey).isFreed());
        assertEquals(12, registrar.created.size());
    }

    @Test
    void fifthLiveContextIsRejectedBeforeAllocationAndReleaseRestoresCapacity() {
        for (long window = 1; window <= IsolatedNativeCallbacks.MAX_WINDOWS; window++) ensureAll(window);
        assertEquals(24, registrar.created.size());
        int attempts = registrar.allocateAttempts;
        assertThrows(IllegalStateException.class, () -> ensureAll(5));
        assertEquals(attempts, registrar.allocateAttempts);
        prepareAndVanillaClose(1);
        registry.release(1);
        ensureAll(5);
        assertEquals(30, registrar.created.size());
    }

    @Test
    void zeroIsRejectedAndSignedNonzeroHandlesAreOpaque() {
        assertThrows(IllegalArgumentException.class, () -> registry.ensureKeyboard(0));
        assertThrows(IllegalArgumentException.class, () -> registry.ensureMouse(0));
        assertThrows(IllegalArgumentException.class, () -> registry.prepareClose(0));
        assertThrows(IllegalArgumentException.class, () -> registry.release(0));
        assertThrows(IllegalArgumentException.class, () -> IsolatedNativeCallbacks.ensureKeyboard(0));
        assertThrows(IllegalArgumentException.class, () -> IsolatedNativeCallbacks.ensureMouse(0));
        assertThrows(IllegalArgumentException.class, () -> IsolatedNativeCallbacks.prepareClose(0));
        assertThrows(IllegalArgumentException.class, () -> IsolatedNativeCallbacks.release(0));
        assertEquals(0, registrar.allocateAttempts);
        for (long window : new long[] {-1, Long.MIN_VALUE, Long.MAX_VALUE, 1}) ensureAll(window);
        assertEquals(24, registrar.created.size());
        registry.release(99);
    }

    @Test
    void partialAllocationFailureKeepsOwnedClosuresBoundedUntilClose() {
        windows.add(WINDOW);
        registry.ensureKeyboard(WINDOW);
        registrar.failAllocateAttempt = 4;
        assertSame(registrar.allocationFailure,
                assertThrows(IllegalStateException.class, () -> registry.ensureMouse(WINDOW)));
        assertEquals(3, registrar.created.size());
        for (int i = 0; i < 20; i++) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> registry.ensureMouse(WINDOW));
            assertSame(registrar.allocationFailure, failure.getCause());
        }
        assertThrows(IllegalStateException.class, () -> registry.ensureKeyboard(WINDOW));
        assertEquals(4, registrar.allocateAttempts);
        prepareAndVanillaClose(WINDOW);
        registry.release(WINDOW);
        assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        registrar.failAllocateAttempt = -1;
        ensureAll(WINDOW);
        assertEquals(9, registrar.created.size());
    }

    @Test
    void firstAllocationFailureDoesNotPermanentlyConsumeReleasedCapacity() {
        windows.add(WINDOW);
        registrar.failAllocateAttempt = 1;
        assertThrows(IllegalStateException.class, () -> registry.ensureKeyboard(WINDOW));
        assertEquals(0, registrar.created.size());
        prepareAndVanillaClose(WINDOW);
        registry.release(WINDOW);
        registrar.failAllocateAttempt = -1;
        ensureAll(WINDOW);
        assertEquals(6, registrar.created.size());
    }

    @Test
    void setterFailureBeforeOrAfterReplacementTracksTheAllocatedPlaceholder() {
        for (boolean afterReplacement : new boolean[] {false, true}) {
            long window = afterReplacement ? 2 : 1;
            windows.add(window);
            if (afterReplacement) registrar.failAfterSetAttempt = registrar.setAttempts + 1;
            else registrar.failBeforeSetAttempt = registrar.setAttempts + 1;
            int allocationsBefore = registrar.created.size();
            assertSame(registrar.setterFailure,
                    assertThrows(IllegalStateException.class, () -> registry.ensureKeyboard(window)));
            assertEquals(allocationsBefore + 1, registrar.created.size());
            assertThrows(IllegalStateException.class, () -> registry.ensureMouse(window));
            assertEquals(allocationsBefore + 1, registrar.created.size());
            prepareAndVanillaClose(window);
            registry.release(window);
            assertTrue(registrar.created.get(allocationsBefore).isFreed());
        }
    }

    @Test
    void failedRegistrationRestoreRetainsForeignForCloseRecoveryBeforeAndAfterSetter() {
        for (boolean afterReplacement : new boolean[] {false, true}) {
            long window = afterReplacement ? 2 : 1;
            windows.add(window);
            ForeignCallback foreign = foreign(IsolatedNativeCallbacks.Slot.KEY, null);
            registrar.put(window, IsolatedNativeCallbacks.Slot.KEY, foreign.callback);
            int ownedIndex = registrar.created.size();
            int failAt = registrar.setAttempts + 2;
            if (afterReplacement) registrar.failAfterSetAttempt = failAt;
            else registrar.failBeforeSetAttempt = failAt;
            assertSame(registrar.setterFailure,
                    assertThrows(IllegalStateException.class, () -> registry.ensureKeyboard(window)));
            assertEquals(ownedIndex + 1, registrar.created.size());
            assertEquals(0, foreign.state.freeCalls);
            assertThrows(IllegalStateException.class, () -> registry.ensureMouse(window));
            assertThrows(IllegalStateException.class, () -> registry.release(window));
            registrar.clearFailures();
            registry.prepareClose(window);
            assertSame(foreign.callback, registrar.current(window, IsolatedNativeCallbacks.Slot.KEY));
            assertFalse(registrar.created.get(ownedIndex).isFreed());
            registrar.vanillaClose(window);
            registry.release(window);
            assertTrue(registrar.created.get(ownedIndex).isFreed());
            assertEquals(0, foreign.state.freeCalls);
            assertTrue(registrar.staticallyFreed.contains(foreign.callback));
        }
    }

    @Test
    void failedCloseForeignRestoreRetainsContextAndExactCallbackForRetry() {
        for (boolean afterReplacement : new boolean[] {false, true}) {
            long window = afterReplacement ? 2 : 1;
            ensureAll(window);
            ForeignCallback foreign = foreign(IsolatedNativeCallbacks.Slot.KEY,
                    registrar.current(window, IsolatedNativeCallbacks.Slot.KEY));
            registrar.put(window, IsolatedNativeCallbacks.Slot.KEY, foreign.callback);
            int allocationsBeforeClose = registrar.created.size();
            int failAt = registrar.setAttempts + 2;
            if (afterReplacement) registrar.failAfterSetAttempt = failAt;
            else registrar.failBeforeSetAttempt = failAt;
            assertSame(registrar.setterFailure,
                    assertThrows(IllegalStateException.class, () -> registry.prepareClose(window)));
            assertThrows(IllegalStateException.class, () -> registry.release(window));
            assertThrows(IllegalStateException.class, () -> registry.ensureKeyboard(window));
            assertEquals(allocationsBeforeClose, registrar.created.size());
            assertEquals(0, foreign.state.freeCalls);
            registrar.clearFailures();
            registry.prepareClose(window);
            assertSame(foreign.callback, registrar.current(window, IsolatedNativeCallbacks.Slot.KEY));
            int setsAfterPrepared = registrar.setAttempts;
            registry.prepareClose(window);
            assertEquals(setsAfterPrepared, registrar.setAttempts);
            registrar.vanillaClose(window);
            registry.release(window);
            assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
            assertEquals(0, foreign.state.freeCalls);
        }
    }

    @Test
    void failedCloseDetachAfterMutationRetainsOwnedClosureForTail() {
        ensureAll(WINDOW);
        registrar.failAfterSetAttempt = registrar.setAttempts + 1;
        assertThrows(IllegalStateException.class, () -> registry.prepareClose(WINDOW));
        assertNull(registrar.current(WINDOW, IsolatedNativeCallbacks.Slot.KEY));
        assertFalse(registrar.created.getFirst().isFreed());
        assertThrows(IllegalStateException.class, () -> registry.release(WINDOW));
        assertThrows(IllegalStateException.class, () -> registry.ensureMouse(WINDOW));
        registrar.clearFailures();
        registry.prepareClose(WINDOW);
        assertFalse(registrar.created.getFirst().isFreed());
        registrar.vanillaClose(WINDOW);
        registry.release(WINDOW);
        assertTrue(registrar.created.stream().allMatch(IsolatedNativeCallbacks.OwnedCallback::isFreed));
        assertEquals(0, registrar.staticFreeCalls);
    }

    @Test
    void recursiveRegistrationCannotAllocateAnotherGroup() {
        windows.add(WINDOW);
        registrar.duringSet = () -> registry.ensureMouse(WINDOW);
        assertThrows(IllegalStateException.class, () -> registry.ensureKeyboard(WINDOW));
        assertEquals(1, registrar.created.size());
        assertThrows(IllegalStateException.class, () -> registry.ensureMouse(WINDOW));
        assertEquals(1, registrar.created.size());
    }

    private void ensureAll(long window) {
        windows.add(window);
        registry.ensureKeyboard(window);
        registry.ensureMouse(window);
    }

    private void prepareAndVanillaClose(long window) {
        registry.prepareClose(window);
        registrar.vanillaClose(window);
    }

    private void invokeAll(long window) {
        for (var slot : IsolatedNativeCallbacks.Slot.values()) invoke(slot, registrar.current(window, slot));
    }

    private static void invoke(IsolatedNativeCallbacks.Slot slot, Callback callback) {
        assertNotNull(callback);
        switch (slot) {
            case KEY -> ((GLFWKeyCallback) callback).invoke(WINDOW, 65, 30, 1, 3);
            case CHAR_MODS -> ((GLFWCharModsCallback) callback).invoke(WINDOW, 0x1f600, 3);
            case CURSOR_POS -> ((GLFWCursorPosCallback) callback).invoke(WINDOW, Double.NaN, Double.POSITIVE_INFINITY);
            case MOUSE_BUTTON -> ((GLFWMouseButtonCallback) callback).invoke(WINDOW, 0, 1, 3);
            case SCROLL -> ((GLFWScrollCallback) callback).invoke(WINDOW, Double.NaN, Double.NEGATIVE_INFINITY);
            case DROP -> ((GLFWDropCallback) callback).invoke(WINDOW, Integer.MAX_VALUE, Long.MIN_VALUE);
        }
    }

    private static Callback wrapperAt(IsolatedNativeCallbacks.Slot slot, long address) {
        return switch (slot) {
            case KEY -> GLFWKeyCallback.create(address);
            case CHAR_MODS -> GLFWCharModsCallback.create(address);
            case CURSOR_POS -> GLFWCursorPosCallback.create(address);
            case MOUSE_BUTTON -> GLFWMouseButtonCallback.create(address);
            case SCROLL -> GLFWScrollCallback.create(address);
            case DROP -> GLFWDropCallback.create(address);
        };
    }

    private ForeignCallback foreign(IsolatedNativeCallbacks.Slot slot, Callback previous) {
        ForeignState state = new ForeignState(slot, previous);
        Callback callback = switch (slot) {
            case KEY -> new GLFWKeyCallback() {
                @Override public void invoke(long w, int key, int scan, int action, int mods) { state.invoke(); }
                @Override public void free() { state.free(super::free); }
            };
            case CHAR_MODS -> new GLFWCharModsCallback() {
                @Override public void invoke(long w, int codepoint, int mods) { state.invoke(); }
                @Override public void free() { state.free(super::free); }
            };
            case CURSOR_POS -> new GLFWCursorPosCallback() {
                @Override public void invoke(long w, double x, double y) { state.invoke(); }
                @Override public void free() { state.free(super::free); }
            };
            case MOUSE_BUTTON -> new GLFWMouseButtonCallback() {
                @Override public void invoke(long w, int button, int action, int mods) { state.invoke(); }
                @Override public void free() { state.free(super::free); }
            };
            case SCROLL -> new GLFWScrollCallback() {
                @Override public void invoke(long w, double x, double y) { state.invoke(); }
                @Override public void free() { state.free(super::free); }
            };
            case DROP -> new GLFWDropCallback() {
                @Override public void invoke(long w, int count, long names) { state.invoke(); }
                @Override public void free() { state.free(super::free); }
            };
        };
        ForeignCallback foreign = new ForeignCallback(callback, state);
        foreignCallbacks.add(foreign);
        return foreign;
    }

    private record ForeignCallback(Callback callback, ForeignState state) {
    }

    private static final class ForeignState {
        private final IsolatedNativeCallbacks.Slot slot;
        private final Callback previous;
        private final AtomicBoolean freed = new AtomicBoolean();
        private int calls;
        private int freeCalls;

        private ForeignState(IsolatedNativeCallbacks.Slot slot, Callback previous) {
            this.slot = slot;
            this.previous = previous;
        }

        private void invoke() {
            calls++;
            if (previous != null) IsolatedNativeCallbacksTest.invoke(slot, previous);
        }

        private void free(Runnable nativeFree) {
            freeCalls++;
            if (freed.compareAndSet(false, true)) nativeFree.run();
        }
    }

    private static final class FakeRegistrar implements IsolatedNativeCallbacks.Registrar {
        private final Map<Long, EnumMap<IsolatedNativeCallbacks.Slot, Callback>> slots = new HashMap<>();
        private final List<IsolatedNativeCallbacks.OwnedCallback> created = new ArrayList<>();
        private final Set<Callback> staticallyFreed = Collections.newSetFromMap(new IdentityHashMap<>());
        private final IllegalStateException allocationFailure = new IllegalStateException("allocation fixture failure");
        private final IllegalStateException setterFailure = new IllegalStateException("setter fixture failure");
        private int allocateAttempts;
        private int setAttempts;
        private int staticFreeCalls;
        private int failAllocateAttempt = -1;
        private int failBeforeSetAttempt = -1;
        private int failAfterSetAttempt = -1;
        private Runnable duringSet;

        @Override
        public IsolatedNativeCallbacks.OwnedCallback allocate(IsolatedNativeCallbacks.Slot slot) {
            if (++allocateAttempts == failAllocateAttempt) throw allocationFailure;
            var owned = IsolatedNativeCallbacks.Registrar.super.allocate(slot);
            created.add(owned);
            return owned;
        }

        @Override
        public Callback set(long window, IsolatedNativeCallbacks.Slot slot, Callback callback) {
            if (++setAttempts == failBeforeSetAttempt) throw setterFailure;
            Callback previous = callback == null ? table(window).remove(slot) : table(window).put(slot, callback);
            if (setAttempts == failAfterSetAttempt) throw setterFailure;
            if (duringSet != null) duringSet.run();
            return previous;
        }

        private void put(long window, IsolatedNativeCallbacks.Slot slot, Callback callback) {
            table(window).put(slot, callback);
        }

        private Callback current(long window, IsolatedNativeCallbacks.Slot slot) {
            var table = slots.get(window);
            return table == null ? null : table.get(slot);
        }

        private EnumMap<IsolatedNativeCallbacks.Slot, Callback> table(long window) {
            return slots.computeIfAbsent(window, ignored -> new EnumMap<>(IsolatedNativeCallbacks.Slot.class));
        }

        private void vanillaClose(long window) {
            var current = slots.remove(window);
            if (current != null) {
                for (Callback callback : current.values()) {
                    assertFalse(callback instanceof IsolatedNativeCallbacks.OwnedCallback,
                            "prepareClose must remove owned current callbacks before vanilla static free");
                    assertTrue(staticallyFreed.add(callback), "native callback may be statically freed only once");
                    Callback.free(callback.address());
                    staticFreeCalls++;
                }
            }
        }

        private void clearFailures() {
            failAllocateAttempt = -1;
            failBeforeSetAttempt = -1;
            failAfterSetAttempt = -1;
            duringSet = null;
        }
    }
}
