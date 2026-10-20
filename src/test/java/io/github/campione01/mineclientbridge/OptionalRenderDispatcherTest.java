package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.*;

import io.github.campione01.mineclientbridge.OptionalRenderDispatcher.Route;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OptionalRenderDispatcherTest {
    private ArrayDeque<Runnable> queued;
    private List<Throwable> failures;
    private AtomicInteger operations;
    private AtomicInteger vanillaSubmissions;

    @BeforeEach
    void resetPublicApiFixtures() {
        queued = new ArrayDeque<>();
        failures = new ArrayList<>();
        operations = new AtomicInteger();
        vanillaSubmissions = new AtomicInteger();
        IxerisApi.initialized = true;
        IxerisApi.checks = 0;
        IxerisApi.failure = null;
        QueueApi.submissions = 0;
        QueueApi.submit = queued::add;
    }

    @Test
    void bothAbsentUseOnlyTheDefaultExecutor() {
        AtomicInteger lookups = new AtomicInteger();
        var dispatcher = OptionalRenderDispatcher.resolve(name -> {
            lookups.incrementAndGet();
            throw new ClassNotFoundException(name);
        });
        assertEquals(Route.VANILLA, dispatcher.dispatch(
                operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        assertNull(dispatcher.bindingFailure());
        assertEquals(2, lookups.get());
        assertEquals(1, vanillaSubmissions.get());
        assertEquals(0, operations.get());
        queued.remove().run();
        assertEquals(1, operations.get());
        assertTrue(failures.isEmpty());
        assertEquals(0, IxerisApi.checks);
        assertEquals(0, QueueApi.submissions);
    }

    @Test
    void cachedBindingsDoNotRepeatClassLookupsOnDispatch() {
        AtomicInteger lookups = new AtomicInteger();
        var dispatcher = OptionalRenderDispatcher.resolve(name -> {
            lookups.incrementAndGet();
            return name.equals(OptionalRenderDispatcher.IXERIS_CLASS) ? IxerisApi.class : QueueApi.class;
        });
        for (int i = 0; i < 20; i++) {
            assertEquals(Route.IXERIS, dispatcher.dispatch(
                    operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        }
        assertEquals(2, lookups.get());
        assertEquals(20, IxerisApi.checks);
        assertEquals(20, QueueApi.submissions);
        assertEquals(0, vanillaSubmissions.get());
        queued.forEach(Runnable::run);
        assertEquals(20, operations.get());
    }

    @Test
    void eitherClassMissingWithTheOtherPresentFallsBackToTheGuardedDefaultExecutor() {
        for (boolean missingIxeris : new boolean[] {true, false}) {
            var dispatcher = OptionalRenderDispatcher.resolve(name -> {
                boolean ixeris = name.equals(OptionalRenderDispatcher.IXERIS_CLASS);
                if (ixeris == missingIxeris) throw new ClassNotFoundException(name);
                return ixeris ? IxerisApi.class : QueueApi.class;
            });
            assertInstanceOf(IllegalStateException.class, dispatcher.bindingFailure());
            assertEquals(Route.VANILLA_IXERIS_UNAVAILABLE, dispatcher.dispatch(
                    operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        }
        assertEquals(2, vanillaSubmissions.get());
        assertEquals(0, IxerisApi.checks);
        assertEquals(0, QueueApi.submissions);
        assertEquals(0, operations.get());
        queued.forEach(Runnable::run);
        assertEquals(2, operations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void linkageFailureFallsBackAndKeepsTheBindingFailureForTheLog() {
        NoClassDefFoundError expected = new NoClassDefFoundError("fixture dependency");
        var dispatcher = OptionalRenderDispatcher.resolve(name -> {
            if (name.equals(OptionalRenderDispatcher.IXERIS_CLASS)) throw expected;
            throw new ClassNotFoundException(name);
        });
        assertSame(expected, dispatcher.bindingFailure());
        assertEquals(Route.VANILLA_IXERIS_UNAVAILABLE, dispatcher.dispatch(
                operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        assertEquals(1, vanillaSubmissions.get());
        queued.remove().run();
        assertEquals(1, operations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void wrappedClassNotFoundIsNotGenuineOptionalAbsence() {
        ClassNotFoundException expected = new ClassNotFoundException("fixture API",
                new NoClassDefFoundError("fixture dependency"));
        var dispatcher = OptionalRenderDispatcher.resolve(name -> {
            if (name.equals(OptionalRenderDispatcher.IXERIS_CLASS)) throw expected;
            throw new ClassNotFoundException(name);
        });
        assertSame(expected, dispatcher.bindingFailure());
        assertEquals(Route.VANILLA_IXERIS_UNAVAILABLE, dispatcher.dispatch(
                operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        assertEquals(1, vanillaSubmissions.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void unavailableBindingFallbackStillRefusesTheWrongThread() {
        var dispatcher = resolved(MissingApi.class, QueueApi.class);
        dispatcher.dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> false);
        queued.remove().run();
        assertEquals(0, operations.get());
        assertInstanceOf(IllegalStateException.class, failures.getFirst());
    }

    @Test
    void fatalClassResolutionErrorsAreRethrownRatherThanCachedAsOptionalFailure() {
        for (Error fatal : new Error[] {
                new OutOfMemoryError("fatal fixture"), new StackOverflowError("fatal fixture")}) {
            for (String target : new String[] {
                    OptionalRenderDispatcher.IXERIS_CLASS, OptionalRenderDispatcher.DISPATCHER_CLASS}) {
                assertSame(fatal, assertThrows(fatal.getClass(), () -> OptionalRenderDispatcher.resolve(name -> {
                    if (name.equals(target)) throw fatal;
                    throw new ClassNotFoundException(name);
                })));
            }
        }
        assertEquals(0, vanillaSubmissions.get());
        assertEquals(0, operations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void resolvingBindingsDoesNotCallInitializationOrQueueApis() {
        assertNull(present().bindingFailure());
        IxerisApi.initialized = false;
        assertNull(present().bindingFailure());
        assertNotNull(resolved(MissingApi.class, QueueApi.class).bindingFailure());
        assertEquals(0, IxerisApi.checks);
        assertEquals(0, QueueApi.submissions);
    }

    @Test
    void missingPrivateInstanceAndWrongReturnBindingsFallBackToTheGuardedDefaultExecutor() {
        List<Route> routes = new ArrayList<>();
        for (Class<?> ixeris : new Class<?>[] {
                MissingApi.class, InstanceInitApi.class, BoxedInitApi.class, NonPublicInitApi.class}) {
            routes.add(resolved(ixeris, QueueApi.class).dispatch(
                    operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        }
        for (Class<?> queue : new Class<?>[] {MissingApi.class, InstanceQueueApi.class, WrongQueueResultApi.class}) {
            routes.add(resolved(IxerisApi.class, queue).dispatch(
                    operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        }
        assertEquals(Collections.nCopies(7, Route.VANILLA_IXERIS_UNAVAILABLE), routes);
        assertEquals(7, vanillaSubmissions.get());
        assertEquals(0, IxerisApi.checks);
        assertEquals(0, QueueApi.submissions);
        queued.forEach(Runnable::run);
        assertEquals(7, operations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void presentButUninitializedUsesTheGuardedDefaultExecutorInsteadOfRunLater() {
        IxerisApi.initialized = false;
        assertEquals(Route.VANILLA_IXERIS_INACTIVE, present().dispatch(
                operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        assertEquals(1, IxerisApi.checks);
        assertEquals(0, QueueApi.submissions);
        assertEquals(1, vanillaSubmissions.get());
        assertEquals(0, operations.get());
        queued.remove().run();
        assertEquals(1, operations.get());
        assertTrue(failures.isEmpty());

        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> false);
        queued.remove().run();
        assertEquals(1, operations.get());
        assertInstanceOf(IllegalStateException.class, failures.getFirst());
        assertEquals(0, QueueApi.submissions);
    }

    @Test
    void initializationIsCheckedOnEveryDispatchSoALaterStartUsesTheIxerisQueue() {
        IxerisApi.initialized = false;
        var dispatcher = present();
        assertEquals(Route.VANILLA_IXERIS_INACTIVE, dispatcher.dispatch(
                operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        IxerisApi.initialized = true;
        assertEquals(Route.IXERIS, dispatcher.dispatch(
                operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true));
        assertEquals(2, IxerisApi.checks);
        assertEquals(1, vanillaSubmissions.get());
        assertEquals(1, QueueApi.submissions);
        queued.forEach(Runnable::run);
        assertEquals(2, operations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void initializationInvocationFailureKeepsOriginalCauseIdentity() {
        IllegalArgumentException expected = new IllegalArgumentException("init fixture failure");
        IxerisApi.failure = expected;
        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true);
        assertSame(expected, failures.getFirst());
        assertEquals(0, QueueApi.submissions);
        assertEquals(0, vanillaSubmissions.get());
    }

    @Test
    void queueInvocationFailureKeepsOriginalCauseIdentityAndDoesNotFallback() {
        IllegalStateException expected = new IllegalStateException("queue fixture failure");
        QueueApi.submit = task -> { throw expected; };
        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true);
        assertSame(expected, failures.getFirst());
        assertEquals(1, QueueApi.submissions);
        assertEquals(0, vanillaSubmissions.get());
        assertEquals(0, operations.get());
    }

    @Test
    void validQueueChecksAffinityAtExecutionRatherThanSubmission() {
        AtomicBoolean mcThread = new AtomicBoolean(false);
        AtomicBoolean renderThread = new AtomicBoolean(false);
        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(),
                mcThread::get, renderThread::get);
        assertEquals(1, queued.size());
        assertTrue(failures.isEmpty());
        mcThread.set(true);
        renderThread.set(true);
        queued.remove().run();
        assertEquals(1, operations.get());
        assertTrue(failures.isEmpty());
        assertEquals(0, vanillaSubmissions.get());
    }

    @Test
    void inlineWrongThreadCompletesTheSuppliedFutureExceptionally() {
        QueueApi.submit = Runnable::run;
        CompletableFuture<Void> future = new CompletableFuture<>();
        present().dispatch(() -> {
            operations.incrementAndGet();
            future.complete(null);
        }, future::completeExceptionally, vanillaQueue(), () -> false, () -> false);
        assertTrue(future.isCompletedExceptionally());
        assertInstanceOf(IllegalStateException.class,
                assertThrows(CompletionException.class, future::join).getCause());
        assertEquals(0, operations.get());
        assertEquals(0, vanillaSubmissions.get());
    }

    @Test
    void queuedExecutionRequiresBothThreadPredicates() {
        for (boolean mcThread : new boolean[] {false, true}) {
            for (boolean renderThread : new boolean[] {false, true}) {
                if (mcThread && renderThread) continue;
                present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(),
                        () -> mcThread, () -> renderThread);
                queued.remove().run();
            }
        }
        assertEquals(3, failures.size());
        assertEquals(0, operations.get());
    }

    @Test
    void predicateExceptionNeverRunsWorkAndKeepsIdentity() {
        IllegalStateException expected = new IllegalStateException("thread predicate fixture failure");
        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(),
                () -> { throw expected; }, () -> true);
        queued.remove().run();
        assertSame(expected, failures.getFirst());
        assertEquals(0, operations.get());
    }

    @Test
    void workExceptionIsDeliveredOnceWithoutReflectionWrapping() {
        AssertionError expected = new AssertionError("operation fixture failure");
        present().dispatch(() -> { throw expected; }, failures::add, vanillaQueue(), () -> true, () -> true);
        Runnable callback = queued.remove();
        callback.run();
        callback.run();
        assertEquals(1, failures.size());
        assertSame(expected, failures.getFirst());
    }

    @Test
    void duplicateAndInlineThenQueuedCallbacksPerformWorkAtMostOnce() {
        QueueApi.submit = callback -> {
            callback.run();
            queued.add(callback);
            queued.add(callback);
        };
        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true);
        queued.forEach(Runnable::run);
        assertEquals(1, operations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void anUnsafeInlineAttemptCannotLaterRunTheSameTaskOnTheRightThread() {
        AtomicBoolean onThread = new AtomicBoolean(false);
        QueueApi.submit = callback -> {
            callback.run();
            queued.add(callback);
        };
        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), onThread::get, onThread::get);
        onThread.set(true);
        queued.remove().run();
        assertEquals(0, operations.get());
        assertEquals(1, failures.size());
    }

    @Test
    void enqueueThenThrowFailsOnceAndCancelsThatGuardedExecution() {
        IllegalStateException expected = new IllegalStateException("after-enqueue fixture failure");
        QueueApi.submit = callback -> {
            queued.add(callback);
            throw expected;
        };
        present().dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> true);
        queued.remove().run();
        assertEquals(0, operations.get());
        assertEquals(List.of(expected), failures);
    }

    @Test
    void defaultExecutorAlsoGuardsQueuedWorkAndReportsSubmissionFailure() {
        var dispatcher = OptionalRenderDispatcher.resolve(name -> { throw new ClassNotFoundException(name); });
        dispatcher.dispatch(operations::incrementAndGet, failures::add, vanillaQueue(), () -> true, () -> false);
        queued.remove().run();
        assertEquals(0, operations.get());
        assertInstanceOf(IllegalStateException.class, failures.getFirst());
        IllegalArgumentException expected = new IllegalArgumentException("default queue fixture failure");
        dispatcher.dispatch(operations::incrementAndGet, failures::add,
                callback -> { throw expected; }, () -> true, () -> true);
        assertSame(expected, failures.get(1));
    }

    @Test
    void callerCancellationCheckRemainsEffectiveWhenQueuedCallbackArrivesLate() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        present().dispatch(() -> {
            if (future.isCancelled()) return;
            operations.incrementAndGet();
            future.complete(null);
        }, future::completeExceptionally, vanillaQueue(), () -> true, () -> true);
        assertTrue(future.cancel(false));
        queued.remove().run();
        assertTrue(future.isCancelled());
        assertEquals(0, operations.get());
    }

    private OptionalRenderDispatcher.Dispatcher present() {
        return resolved(IxerisApi.class, QueueApi.class);
    }

    private OptionalRenderDispatcher.Dispatcher resolved(Class<?> ixeris, Class<?> queue) {
        return OptionalRenderDispatcher.resolve(name ->
                name.equals(OptionalRenderDispatcher.IXERIS_CLASS) ? ixeris : queue);
    }

    private Consumer<Runnable> vanillaQueue() {
        return callback -> {
            vanillaSubmissions.incrementAndGet();
            queued.add(callback);
        };
    }

    public static final class IxerisApi {
        private static boolean initialized;
        private static int checks;
        private static RuntimeException failure;

        public static boolean isInitialized() {
            checks++;
            if (failure != null) throw failure;
            return initialized;
        }
    }

    public static final class QueueApi {
        private static int submissions;
        private static Consumer<Runnable> submit;

        public static void runLater(Runnable callback) {
            submissions++;
            submit.accept(callback);
        }
    }

    public static final class MissingApi {
    }

    public static final class InstanceInitApi {
        public boolean isInitialized() { return true; }
    }

    public static final class BoxedInitApi {
        public static Boolean isInitialized() { return true; }
    }

    private static final class NonPublicInitApi {
        public static boolean isInitialized() { return true; }
    }

    public static final class InstanceQueueApi {
        public void runLater(Runnable callback) { callback.run(); }
    }

    public static final class WrongQueueResultApi {
        public static Object runLater(Runnable callback) { return callback; }
    }
}
