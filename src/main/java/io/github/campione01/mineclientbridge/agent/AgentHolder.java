package io.github.campione01.mineclientbridge.agent;

import java.lang.instrument.Instrumentation;
import java.util.Map;

/** [Arcane Academy fork] Loaded by the system loader, also accessible from FML via reflection. */
public final class AgentHolder {
    private static Instrumentation instrumentation;
    private static HotSwapEngine engine;

    private AgentHolder() { }

    static void install(Instrumentation value, HotSwapEngine service) {
        instrumentation = value;
        engine = service;
    }

    public static Map<String, Object> hotswap() {
        if (instrumentation == null) return Map.of("ok", false, "error", "agent_not_loaded", "detail", "agent not loaded");
        return engine.swap();
    }
}
