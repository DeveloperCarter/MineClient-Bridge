package io.github.campione01.mineclientbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.system.Callback;

public final class IsolatedNativeCallbacks {
    static final int MAX_WINDOWS = 4;

    private static final Registry CALLBACKS = new Registry(new GlfwRegistrar());

    private IsolatedNativeCallbacks() {
    }

    /** Call on the render thread before another mod wraps the keyboard callbacks. */
    public static void ensureKeyboard(long window) {
        CALLBACKS.ensureKeyboard(window);
    }

    /** Call on the render thread before another mod wraps the mouse callbacks. */
    public static void ensureMouse(long window) {
        CALLBACKS.ensureMouse(window);
    }

    /** Call on the render thread before normal callback cleanup, while the window is alive. */
    public static void prepareClose(long window) {
        CALLBACKS.prepareClose(window);
    }

    /** Call after prepareClose, normal callback cleanup, and native window destruction. */
    public static void release(long window) {
        CALLBACKS.release(window);
    }

    interface Registrar {
        default OwnedCallback allocate(Slot slot) {
            return slot.allocate();
        }

        Callback set(long window, Slot slot, Callback callback);
    }

    enum Slot {
        KEY, CHAR_MODS, CURSOR_POS, MOUSE_BUTTON, SCROLL, DROP;

        OwnedCallback allocate() {
            // Allocate the guard before the native closure can acquire resources.
            AtomicBoolean freed = new AtomicBoolean();
            return switch (this) {
                case KEY -> new NoOpKeyCallback(freed);
                case CHAR_MODS -> new NoOpCharModsCallback(freed);
                case CURSOR_POS -> new NoOpCursorPosCallback(freed);
                case MOUSE_BUTTON -> new NoOpMouseButtonCallback(freed);
                case SCROLL -> new NoOpScrollCallback(freed);
                case DROP -> new NoOpDropCallback(freed);
            };
        }
    }

    private enum Group {
        KEYBOARD(1, Slot.KEY, Slot.CHAR_MODS),
        MOUSE(2, Slot.CURSOR_POS, Slot.MOUSE_BUTTON, Slot.SCROLL, Slot.DROP);

        private final int mask;
        private final Slot[] slots;

        Group(int mask, Slot... slots) {
            this.mask = mask;
            this.slots = slots;
        }
    }

    sealed interface OwnedCallback permits NoOpKeyCallback, NoOpCharModsCallback,
            NoOpCursorPosCallback, NoOpMouseButtonCallback, NoOpScrollCallback, NoOpDropCallback {
        default Callback callback() {
            return (Callback) this;
        }

        boolean isFreed();

        void free();
    }

    static final class Registry {
        private final Registrar registrar;
        private final Map<Long, Context> contexts = new HashMap<>();

        Registry(Registrar registrar) {
            this.registrar = Objects.requireNonNull(registrar, "registrar");
        }

        void ensureKeyboard(long window) {
            ensure(window, Group.KEYBOARD);
        }

        void ensureMouse(long window) {
            ensure(window, Group.MOUSE);
        }

        private synchronized void ensure(long window, Group group) {
            requireWindow(window);
            Context context = contexts.get(window);
            if (context == null) {
                if (contexts.size() >= MAX_WINDOWS) {
                    throw new IllegalStateException("Too many isolated native callback windows");
                }
                context = new Context();
                contexts.put(window, context);
            }
            if (context.closing) {
                throw new IllegalStateException("Native callback window is closing");
            }
            if (context.failure != null) {
                throw new IllegalStateException("Native callback registration previously failed", context.failure);
            }
            if (context.preparing) {
                throw new IllegalStateException("Recursive native callback registration");
            }
            if ((context.readyGroups & group.mask) != 0) {
                return;
            }

            context.preparing = true;
            try {
                for (Slot slot : group.slots) {
                    OwnedCallback owned = Objects.requireNonNull(registrar.allocate(slot), "callback");
                    // Track before calling a setter that might throw after replacing its slot.
                    context.owned[slot.ordinal()] = owned;
                    Callback previous = registrar.set(window, slot, owned.callback());
                    if (previous != null) {
                        context.pendingRestore[slot.ordinal()] = previous;
                        registrar.set(window, slot, previous);
                        context.pendingRestore[slot.ordinal()] = null;
                        owned.free();
                    }
                }
                context.readyGroups |= group.mask;
            } catch (RuntimeException | Error failure) {
                context.failure = failure;
                throw failure;
            } finally {
                context.preparing = false;
            }
        }

        synchronized void prepareClose(long window) {
            requireWindow(window);
            Context context = contexts.get(window);
            if (context == null) {
                return;
            }
            if (context.preparing) {
                throw new IllegalStateException("Cannot close during native callback registration");
            }
            if (context.closePrepared) {
                return;
            }
            context.closing = true;
            for (Slot slot : Slot.values()) {
                Callback pending = context.pendingRestore[slot.ordinal()];
                if (pending != null) {
                    registrar.set(window, slot, pending);
                    context.pendingRestore[slot.ordinal()] = null;
                }
                OwnedCallback owned = context.owned[slot.ordinal()];
                if (owned == null || owned.isFreed()) {
                    continue;
                }
                Callback current = registrar.set(window, slot, null);
                if (current != null && current.address() == owned.callback().address()) {
                    // Vanilla uses static Callback.free(address), bypassing our instance guard.
                    owned.free();
                } else if (current != null) {
                    context.pendingRestore[slot.ordinal()] = current;
                    registrar.set(window, slot, current);
                    context.pendingRestore[slot.ordinal()] = null;
                }
            }
            // Setter failures propagate, leaving this context for a retry while the window is alive.
            context.closePrepared = true;
        }

        synchronized void release(long window) {
            requireWindow(window);
            Context context = contexts.get(window);
            if (context == null) {
                return;
            }
            if (!context.closePrepared) {
                throw new IllegalStateException("Native callback close was not prepared");
            }
            contexts.remove(window);
            Throwable failure = null;
            for (OwnedCallback owned : context.owned) {
                if (owned == null) {
                    continue;
                }
                try {
                    owned.free();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static void requireWindow(long window) {
        if (window == 0) {
            throw new IllegalArgumentException("Native window handle must be nonzero");
        }
    }

    private static final class Context {
        private final OwnedCallback[] owned = new OwnedCallback[Slot.values().length];
        private final Callback[] pendingRestore = new Callback[Slot.values().length];
        private int readyGroups;
        private boolean preparing;
        private boolean closing;
        private boolean closePrepared;
        private Throwable failure;
    }

    private static final class GlfwRegistrar implements Registrar {
        @Override
        public Callback set(long window, Slot slot, Callback callback) {
            return switch (slot) {
                case KEY -> GLFW.glfwSetKeyCallback(window, (GLFWKeyCallback) callback);
                case CHAR_MODS -> GLFW.glfwSetCharModsCallback(window, (GLFWCharModsCallback) callback);
                case CURSOR_POS -> GLFW.glfwSetCursorPosCallback(window, (GLFWCursorPosCallback) callback);
                case MOUSE_BUTTON -> GLFW.glfwSetMouseButtonCallback(window, (GLFWMouseButtonCallback) callback);
                case SCROLL -> GLFW.glfwSetScrollCallback(window, (GLFWScrollCallback) callback);
                case DROP -> GLFW.glfwSetDropCallback(window, (GLFWDropCallback) callback);
            };
        }
    }

    private static final class NoOpKeyCallback extends GLFWKeyCallback implements OwnedCallback {
        private final AtomicBoolean freed;

        private NoOpKeyCallback(AtomicBoolean freed) {
            this.freed = freed;
        }

        @Override
        public void invoke(long window, int key, int scancode, int action, int mods) {
        }

        @Override
        public boolean isFreed() {
            return freed.get();
        }

        @Override
        public void free() {
            if (freed.compareAndSet(false, true)) super.free();
        }
    }

    private static final class NoOpCharModsCallback extends GLFWCharModsCallback implements OwnedCallback {
        private final AtomicBoolean freed;

        private NoOpCharModsCallback(AtomicBoolean freed) {
            this.freed = freed;
        }

        @Override
        public void invoke(long window, int codepoint, int mods) {
        }

        @Override
        public boolean isFreed() {
            return freed.get();
        }

        @Override
        public void free() {
            if (freed.compareAndSet(false, true)) super.free();
        }
    }

    private static final class NoOpCursorPosCallback extends GLFWCursorPosCallback implements OwnedCallback {
        private final AtomicBoolean freed;

        private NoOpCursorPosCallback(AtomicBoolean freed) {
            this.freed = freed;
        }

        @Override
        public void invoke(long window, double x, double y) {
        }

        @Override
        public boolean isFreed() {
            return freed.get();
        }

        @Override
        public void free() {
            if (freed.compareAndSet(false, true)) super.free();
        }
    }

    private static final class NoOpMouseButtonCallback extends GLFWMouseButtonCallback implements OwnedCallback {
        private final AtomicBoolean freed;

        private NoOpMouseButtonCallback(AtomicBoolean freed) {
            this.freed = freed;
        }

        @Override
        public void invoke(long window, int button, int action, int mods) {
        }

        @Override
        public boolean isFreed() {
            return freed.get();
        }

        @Override
        public void free() {
            if (freed.compareAndSet(false, true)) super.free();
        }
    }

    private static final class NoOpScrollCallback extends GLFWScrollCallback implements OwnedCallback {
        private final AtomicBoolean freed;

        private NoOpScrollCallback(AtomicBoolean freed) {
            this.freed = freed;
        }

        @Override
        public void invoke(long window, double x, double y) {
        }

        @Override
        public boolean isFreed() {
            return freed.get();
        }

        @Override
        public void free() {
            if (freed.compareAndSet(false, true)) super.free();
        }
    }

    private static final class NoOpDropCallback extends GLFWDropCallback implements OwnedCallback {
        private final AtomicBoolean freed;

        private NoOpDropCallback(AtomicBoolean freed) {
            this.freed = freed;
        }

        @Override
        public void invoke(long window, int count, long names) {
        }

        @Override
        public boolean isFreed() {
            return freed.get();
        }

        @Override
        public void free() {
            if (freed.compareAndSet(false, true)) super.free();
        }
    }
}
