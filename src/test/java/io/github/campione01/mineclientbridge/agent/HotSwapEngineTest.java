package io.github.campione01.mineclientbridge.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spike.fixture.First;
import spike.fixture.Second;

/** [Arcane Academy fork] Pure JVM tests: no Minecraft/FML classes loaded. */
class HotSwapEngineTest {
    @TempDir Path root;
    private final List<ClassDefinition[]> attempts = new ArrayList<>();
    private RuntimeException refusal;

    private Path disk(Class<?> type, String content) throws Exception {
        return disk(type.getName(), content);
    }

    private Path disk(String name, String content) throws Exception {
        Path file = root.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private void define(HotSwapEngine engine, Class<?> type, Path disk) throws Exception {
        engine.recordDefinition(type.getClassLoader(), type.getName(), Files.readAllBytes(disk));
    }

    @Test void noAgentHasClearError() {
        assertEquals("agent not loaded", AgentHolder.hotswap().get("detail"));
    }

    @Test void packageBoundariesAndProtectedClassesAreNeverAllowed() {
        HotSwapEngine engine = new HotSwapEngine(null, List.of(root), List.of("dev.example", "net.minecraft", "java"));
        assertTrue(engine.allowed("dev.example.Hud"));
        assertFalse(engine.allowed("dev.examples.Other"));
        assertFalse(engine.allowed("net.minecraft.client.Minecraft"));
        assertFalse(engine.allowed("java.lang.String"));
        assertFalse(engine.allowed("another.mod.Hud"));
    }

    @Test void unchangedAndUnloadedFilesHaveSeparateCounts() throws Exception {
        // Use an application-style allowlist; the bridge itself is deliberately hard-blocked.
        HotSwapEngine engine = applicationEngine(First.class);
        Path first = disk(First.class, "old");
        define(engine, First.class, first);
        disk("spike.fixture.NewClass", "new");
        Map<String, Object> report = engine.swap();
        assertEquals(1, report.get("unchanged_count"));
        assertEquals(List.of("spike.fixture.NewClass"), report.get("new_on_disk"));
        assertEquals(false, report.get("restart_required"));
        assertEquals(HotSwapEngine.STATIC_FIELDS_NOTE, report.get("static_fields_note"));
        assertTrue(attempts.isEmpty());
    }

    @Test void changedClassesAreOneBatchAndOnlySuccessfulHashesAdvance() throws Exception {
        HotSwapEngine engine = applicationEngine(First.class, Second.class);
        Path first = disk(First.class, "old1"), second = disk(Second.class, "old2");
        define(engine, First.class, first);
        define(engine, Second.class, second);
        Files.writeString(first, "new1"); Files.writeString(second, "new2");
        Map<String, Object> report = engine.swap();
        assertEquals(2, ((List<?>) report.get("swapped")).size());
        assertEquals(1, attempts.size()); assertEquals(2, attempts.getFirst().length);
        assertEquals(2, engine.swap().get("unchanged_count"));
        assertEquals(1, attempts.size());
        Files.writeString(first, "old1");
        assertEquals(1, ((List<?>) engine.swap().get("swapped")).size(), "reverting source is another change");
    }

    @Test void transformedClassesAreSkippedEvenWithoutDiskChanges() throws Exception {
        HotSwapEngine engine = applicationEngine(First.class);
        disk(First.class, "raw");
        engine.recordDefinition(First.class.getClassLoader(), First.class.getName(), "loader transformed".getBytes());
        Map<String, Object> report = engine.swap();
        assertEquals(List.of(First.class.getName()), report.get("transformed"));
        assertEquals(true, report.get("restart_required")); assertTrue(attempts.isEmpty());
    }

    @Test void refusedBatchRemainsPendingAndReportsJvmReasonForEveryClass() throws Exception {
        HotSwapEngine engine = applicationEngine(First.class, Second.class);
        Path first = disk(First.class, "old1"), second = disk(Second.class, "old2");
        define(engine, First.class, first); define(engine, Second.class, second);
        Files.writeString(first, "new1"); Files.writeString(second, "new2");
        refusal = new UnsupportedOperationException("class redefinition failed: attempted to change the schema");
        Map<String, Object> report = engine.swap();
        assertEquals(List.of(), report.get("swapped"));
        assertEquals(2, ((List<?>) report.get("refused")).size());
        assertTrue(report.get("refused").toString().contains("UnsupportedOperationException"));
        assertEquals(true, report.get("restart_required"));
        refusal = null;
        assertEquals(2, ((List<?>) engine.swap().get("swapped")).size());
    }

    @Test void missingBaselineFailsClosed() throws Exception {
        HotSwapEngine engine = applicationEngine(First.class);
        disk(First.class, "new");
        assertEquals(List.of(First.class.getName()), engine.swap().get("transformed"));
        assertTrue(attempts.isEmpty());
    }

    @Test void generatedHiddenLambdasAreNotMistakenForTransformedClassFiles() {
        Class<?> hidden = First.hiddenType();
        assertTrue(hidden.isHidden());
        Map<String, Object> report = applicationEngine(hidden).swap();
        assertEquals(1, report.get("skipped_hidden_count"));
        assertEquals(List.of(), report.get("transformed"));
        assertEquals(false, report.get("restart_required"));
    }

    @Test void missingFileRefusesWholePendingBatch() throws Exception {
        HotSwapEngine engine = applicationEngine(First.class, Second.class);
        Path first = disk(First.class, "old1"), second = disk(Second.class, "old2");
        define(engine, First.class, first); define(engine, Second.class, second);
        Files.delete(first); Files.writeString(second, "new2");
        Map<String, Object> report = engine.swap();
        assertEquals(2, ((List<?>) report.get("refused")).size());
        assertTrue(attempts.isEmpty());
    }

    private HotSwapEngine applicationEngine(Class<?>... loaded) {
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Instrumentation.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getAllLoadedClasses" -> loaded;
                    case "redefineClasses" -> {
                        attempts.add((ClassDefinition[]) args[0]);
                        if (refusal != null) throw refusal;
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        // Fixtures live outside the bridge package so the production denylist remains exercised.
        return new HotSwapEngine(instrumentation, List.of(root), List.of("spike.fixture"));
    }
}
