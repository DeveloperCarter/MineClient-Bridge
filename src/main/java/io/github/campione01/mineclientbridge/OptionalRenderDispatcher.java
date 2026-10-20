package io.github.campione01.mineclientbridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class OptionalRenderDispatcher {
    static final String IXERIS_CLASS = "me.decce.ixeris.core.Ixeris";
    static final String DISPATCHER_CLASS = "me.decce.ixeris.core.threading.RenderThreadDispatcher";

    public enum Route {
        VANILLA, IXERIS, VANILLA_IXERIS_INACTIVE, VANILLA_IXERIS_UNAVAILABLE
    }

    private OptionalRenderDispatcher() {
    }

    public static Route dispatch(Runnable task, Consumer<Throwable> fail,
            Consumer<Runnable> vanillaExecutor, BooleanSupplier mcThread, BooleanSupplier renderThread) {
        return Cached.DISPATCHER.dispatch(task, fail, vanillaExecutor, mcThread, renderThread);
    }

    static Throwable bindingFailure() {
        return Cached.DISPATCHER.bindingFailure();
    }

    private static final class Cached {
        private static final Dispatcher DISPATCHER = resolve(name ->
                Class.forName(name, false, OptionalRenderDispatcher.class.getClassLoader()));
    }

    @FunctionalInterface
    interface ClassLookup {
        Class<?> load(String name) throws ClassNotFoundException;
    }

    static Dispatcher resolve(ClassLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        Loaded ixeris = load(lookup, IXERIS_CLASS);
        Loaded queue = load(lookup, DISPATCHER_CLASS);
        if (ixeris.absent && queue.absent) {
            return new Dispatcher(null, null, null);
        }
        if (ixeris.failure != null || queue.failure != null) {
            return new Dispatcher(null, null, ixeris.failure != null ? ixeris.failure : queue.failure);
        }
        if (ixeris.absent || queue.absent) {
            return new Dispatcher(null, null,
                    new IllegalStateException("Ixeris render dispatcher API is only partially available"));
        }
        try {
            Method initialized = publicStatic(ixeris.type, "isInitialized", boolean.class);
            Method runLater = publicStatic(queue.type, "runLater", void.class, Runnable.class);
            return new Dispatcher(initialized, runLater, null);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return new Dispatcher(null, null, failure);
        }
    }

    private static Loaded load(ClassLookup lookup, String name) {
        try {
            return new Loaded(Objects.requireNonNull(lookup.load(name), name), false, null);
        } catch (ClassNotFoundException absent) {
            // A wrapped dependency/linkage failure is not genuine absence of the optional API.
            return new Loaded(null, absent.getCause() == null,
                    absent.getCause() == null ? null : absent);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return new Loaded(null, false, failure);
        }
    }

    private static Method publicStatic(Class<?> owner, String name, Class<?> result, Class<?>... arguments)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, arguments);
        if (!Modifier.isPublic(owner.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != result) {
            throw new IllegalStateException("Unsupported public Ixeris method: " + owner.getName() + "." + name);
        }
        return method;
    }

    private record Loaded(Class<?> type, boolean absent, Throwable failure) {
    }

    static final class Dispatcher {
        private final Method initialized;
        private final Method runLater;
        private final Throwable bindingFailure;

        private Dispatcher(Method initialized, Method runLater, Throwable bindingFailure) {
            this.initialized = initialized;
            this.runLater = runLater;
            this.bindingFailure = bindingFailure;
        }

        Throwable bindingFailure() {
            return bindingFailure;
        }

        Route dispatch(Runnable task, Consumer<Throwable> fail, Consumer<Runnable> vanillaExecutor,
                BooleanSupplier mcThread, BooleanSupplier renderThread) {
            Objects.requireNonNull(fail, "fail");
            AtomicBoolean claimed = new AtomicBoolean();
            Runnable guarded = () -> {
                if (!claimed.compareAndSet(false, true)) return;
                try {
                    boolean sameMinecraftThread = mcThread.getAsBoolean();
                    boolean onRenderThread = renderThread.getAsBoolean();
                    if (!sameMinecraftThread || !onRenderThread) {
                        throw new IllegalStateException("Bridge work is not on the Minecraft render thread");
                    }
                    task.run();
                } catch (Throwable failure) {
                    fail.accept(failure);
                }
            };
            Route route = runLater != null ? Route.IXERIS
                    : bindingFailure != null ? Route.VANILLA_IXERIS_UNAVAILABLE : Route.VANILLA;
            try {
                Objects.requireNonNull(task, "task");
                Objects.requireNonNull(vanillaExecutor, "vanillaExecutor");
                Objects.requireNonNull(mcThread, "mcThread");
                Objects.requireNonNull(renderThread, "renderThread");
                if (route == Route.IXERIS && !Boolean.TRUE.equals(initialized.invoke(null))) {
                    // Without Ixeris's startup hook the client never leaves Minecraft's own thread.
                    route = Route.VANILLA_IXERIS_INACTIVE;
                }
                if (route == Route.IXERIS) {
                    // Initialization can race submission; the callback itself must enforce affinity.
                    runLater.invoke(null, guarded);
                } else {
                    // Minecraft's queue drains on the render thread with or without Ixeris; the guard checks it.
                    vanillaExecutor.accept(guarded);
                }
            } catch (Throwable failure) {
                if (claimed.compareAndSet(false, true)) fail.accept(unwrap(failure));
            }
            return route;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
    }
}
