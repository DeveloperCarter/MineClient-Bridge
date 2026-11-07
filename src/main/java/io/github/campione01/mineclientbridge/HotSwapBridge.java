package io.github.campione01.mineclientbridge;

import com.google.gson.JsonObject;

/** [Arcane Academy fork] The only connection from the mod to the optional Java agent. */
final class HotSwapBridge {
    private HotSwapBridge() { }

    static JsonObject swap() throws ReflectiveOperationException {
        // FML loads the jar again in its module layer. A direct AgentHolder reference would see
        // that second copy's empty static fields. Reach the premain copy through the system
        // loader, and pass only JDK Map/List/String values across the loader boundary.
        try {
            Class<?> holder = Class.forName("io.github.campione01.mineclientbridge.agent.AgentHolder",
                    true, ClassLoader.getSystemClassLoader());
            return new com.google.gson.Gson().toJsonTree(holder.getMethod("hotswap").invoke(null)).getAsJsonObject();
        } catch (ClassNotFoundException e) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "agent_not_loaded");
            result.addProperty("detail", "agent not loaded");
            return result;
        }
    }
}
