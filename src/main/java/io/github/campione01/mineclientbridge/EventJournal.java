package io.github.campione01.mineclientbridge;

import java.util.ArrayDeque;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * [Arcane Academy fork] A bounded journal of client events that happen between two queries:
 * chat and action-bar messages, screens opening and closing, sounds starting, the bridge's own
 * inputs, and events other development mods send (see {@link ModLink}).
 *
 * <p>An agent reads it from a cursor: remember {@code next} from one read and pass it as
 * {@code since} to the next, so nothing is read twice and nothing in between is missed. When more
 * than {@link #CAPACITY} events arrive between reads the oldest are dropped and counted.</p>
 *
 * <p>Only NeoForge events are used, no mixins. Recording is thread-safe: game events arrive on
 * the client thread, mod events on whichever thread produced them, reads on HTTP workers.</p>
 */
final class EventJournal {
    static final int CAPACITY = 2048;
    static final int MAX_READ = 500;
    private static final int MAX_TEXT = 512;

    private static final ArrayDeque<JsonObject> EVENTS = new ArrayDeque<>();
    private static long nextSequence = 1;
    private static long dropped;
    // The game tick for each event. A supplier, so unit tests can record without a Minecraft instance.
    static java.util.function.LongSupplier tickSource = () -> {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null ? mc.level.getGameTime() : -1;
    };

    private EventJournal() {
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent event) -> {
            JsonObject data = new JsonObject();
            data.addProperty("text", bounded(event.getMessage().getString()));
            boolean overlay = event instanceof ClientChatReceivedEvent.System system && system.isOverlay();
            String type = overlay ? "action_bar" : event.isSystem() ? "system_chat" : "chat";
            record(type, data);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Opening event) -> {
            JsonObject data = new JsonObject();
            data.addProperty("screen", event.getNewScreen().getClass().getName());
            data.addProperty("title", bounded(event.getNewScreen().getTitle().getString()));
            record("screen_open", data);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Closing event) -> {
            JsonObject data = new JsonObject();
            data.addProperty("screen", event.getScreen().getClass().getName());
            record("screen_close", data);
        });
        NeoForge.EVENT_BUS.addListener((PlaySoundEvent event) -> {
            SoundInstance sound = event.getSound();
            if (sound == null) return;
            JsonObject data = new JsonObject();
            data.addProperty("sound", sound.getLocation().toString());
            data.addProperty("source", sound.getSource().getName());
            data.addProperty("looping", sound.isLooping());
            // The sound is not always resolved yet when this event fires; its volume and pitch then
            // throw, and the location alone still identifies it.
            try {
                data.addProperty("volume", sound.getVolume());
                data.addProperty("pitch", sound.getPitch());
            } catch (RuntimeException e) {
                data.addProperty("volume_unresolved", true);
            }
            data.addProperty("pos", String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", sound.getX(), sound.getY(), sound.getZ()));
            record("sound", data);
        });
    }

    static void record(String type, JsonObject data) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("game_tick", tickSource.getAsLong());
        event.addProperty("time_ms", System.currentTimeMillis());
        event.add("data", data);
        synchronized (EVENTS) {
            event.addProperty("seq", nextSequence++);
            EVENTS.addLast(event);
            while (EVENTS.size() > CAPACITY) {
                EVENTS.removeFirst();
                dropped++;
            }
        }
    }

    /** For other mods' events: their JSON as data, or the raw text when it does not parse. */
    static void recordExternal(String type, String json) {
        JsonObject data;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            data = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            if (!parsed.isJsonObject()) data.add("value", parsed);
        } catch (RuntimeException e) {
            data = new JsonObject();
            data.addProperty("text", bounded(json));
        }
        record(type == null ? "mod" : bounded(type), data);
    }

    /** Events after {@code since}, oldest first, optionally only {@code types}, at most {@code limit}. */
    static JsonObject read(long since, Set<String> types, int limit) {
        JsonObject out = new JsonObject();
        com.google.gson.JsonArray events = new com.google.gson.JsonArray();
        boolean more = false;
        synchronized (EVENTS) {
            // A cursor past the newest event (for example Long.MAX_VALUE) reads nothing and returns
            // the newest sequence number: how a waiter starts "from now".
            long last = Math.min(since, nextSequence - 1);
            long oldest = EVENTS.isEmpty() ? nextSequence : EVENTS.peekFirst().get("seq").getAsLong();
            out.addProperty("missed", Math.max(0, oldest - Math.min(since, nextSequence - 1) - 1));
            for (JsonObject event : EVENTS) {
                long seq = event.get("seq").getAsLong();
                if (seq <= since) continue;
                if (events.size() >= limit) {
                    more = true;
                    break;
                }
                last = seq;
                if (types.isEmpty() || types.contains(event.get("type").getAsString())) {
                    events.add(event.deepCopy());
                }
            }
            if (!more) last = Math.max(last, nextSequence - 1);
            out.addProperty("dropped_total", dropped);
            out.addProperty("next", last);
        }
        out.addProperty("ok", true);
        out.add("events", events);
        out.addProperty("more", more);
        return out;
    }

    static void clearForTest() {
        synchronized (EVENTS) {
            EVENTS.clear();
            nextSequence = 1;
            dropped = 0;
        }
    }

    private static String bounded(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "...";
    }
}
