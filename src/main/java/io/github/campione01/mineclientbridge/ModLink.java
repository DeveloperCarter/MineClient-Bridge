package io.github.campione01.mineclientbridge;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.InterModProcessEvent;

/**
 * [Arcane Academy fork] Optional link to development mods in the same client, through NeoForge's
 * InterModComms, so neither side compiles against the other. A mod may send, in its
 * {@code InterModEnqueueEvent}:
 *
 * <ul>
 * <li>{@code probe_reader}: a {@code Function<String, String>} from a probe name to a JSON
 *     result. The bridge calls it on an HTTP worker thread, never the game threads; the mod moves
 *     the read to the right thread itself. The {@code probe} query uses it.</li>
 * <li>{@code journal_source}: a {@code Consumer<BiConsumer<String, String>>}. The bridge calls it
 *     once with a sink taking an event type and its JSON, which goes into the event journal.</li>
 * </ul>
 *
 * <p>Payloads of any other type are ignored and logged.</p>
 */
final class ModLink {
    private static volatile Function<String, String> probeReader;
    private static volatile String probeOwner = "";

    private ModLink() {
    }

    static void install(IEventBus modEventBus) {
        modEventBus.addListener(ModLink::process);
    }

    @SuppressWarnings("unchecked")
    private static void process(InterModProcessEvent event) {
        event.getIMCStream().forEach(message -> {
            Object payload = message.messageSupplier().get();
            switch (message.method()) {
                case "probe_reader" -> {
                    if (payload instanceof Function<?, ?> reader) {
                        probeReader = (Function<String, String>) reader;
                        probeOwner = message.senderModId();
                        BridgeLog.LOGGER.info("[MineClient Bridge] probe reader from {}", message.senderModId());
                        return;
                    }
                }
                case "journal_source" -> {
                    if (payload instanceof Consumer<?> source) {
                        String sender = message.senderModId();
                        BiConsumer<String, String> sink = (type, json) -> EventJournal.recordExternal(type, json);
                        ((Consumer<BiConsumer<String, String>>) source).accept(sink);
                        BridgeLog.LOGGER.info("[MineClient Bridge] journal source from {}", sender);
                        return;
                    }
                }
                default -> {
                }
            }
            BridgeLog.LOGGER.warn("[MineClient Bridge] ignored IMC {} from {}", message.method(), message.senderModId());
        });
    }

    /** The reader's JSON, or null when no mod registered one. */
    static String readProbe(String name) {
        Function<String, String> reader = probeReader;
        return reader == null ? null : reader.apply(name);
    }

    static String probeOwner() {
        return probeOwner;
    }

    static boolean linked() {
        return probeReader != null;
    }
}
