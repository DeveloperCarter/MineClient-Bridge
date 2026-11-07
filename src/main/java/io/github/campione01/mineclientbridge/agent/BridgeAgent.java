package io.github.campione01.mineclientbridge.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** [Arcane Academy fork] Optional development agent; no Minecraft/FML dependencies. */
public final class BridgeAgent {
    private BridgeAgent() { }

    public static void premain(String arguments, Instrumentation instrumentation) {
        HotSwapEngine engine = HotSwapEngine.fromSystemProperties(instrumentation);
        AgentHolder.install(instrumentation, engine);
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                    ProtectionDomain domain, byte[] bytes) {
                // ModLauncher transforms before defineClass, so these are the bytes the JVM sees,
                // not the raw compiler output. Do not replace the startup baseline on redefine.
                if (redefining == null && name != null) {
                    engine.recordDefinition(loader, name.replace('/', '.'), bytes);
                }
                return null;
            }
        }, false);
    }
}
