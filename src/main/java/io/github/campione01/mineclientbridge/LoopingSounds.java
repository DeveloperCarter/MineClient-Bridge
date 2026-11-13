package io.github.campione01.mineclientbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.sounds.SoundSource;

/** [Arcane Academy fork] Client-thread snapshots, capped64 and cleared when loops stop or disconnect. */
final class LoopingSounds {
    private static final int LIMIT = 64;
    private static final LinkedHashMap<SoundInstance, Tracked> TRACKED = new LinkedHashMap<>();
    private static long tick;
    private static long nextId;
    private static final class Tracked {
        final long id = ++nextId;
        final long started = tick;
        long published;
        LoopSoundValues previous;
    }
    private LoopingSounds() {}
    static synchronized void started(SoundInstance sound) {
        if (!sound.isLooping()) return;
        TRACKED.putIfAbsent(sound, new Tracked());
        if (TRACKED.size() > LIMIT) {
            var oldest = TRACKED.entrySet().iterator().next();
            stopped(oldest.getKey(), oldest.getValue(), "capacity");
            TRACKED.remove(oldest.getKey());
        }
    }
    static synchronized void tick() {
        tick++;
        sample(Minecraft.getInstance());
    }
    private static LoopSoundValues values(Minecraft mc, SoundInstance sound) {
        return new LoopSoundValues(sound.getVolume(), sound.getPitch(),
                mc.options.getSoundSourceVolume(sound.getSource()), mc.options.getSoundSourceVolume(SoundSource.MASTER));
    }
    private static synchronized void sample(Minecraft mc) {
        var iterator = TRACKED.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var sound = entry.getKey();
            var tracked = entry.getValue();
            boolean active = mc.getSoundManager().isActive(sound);
            boolean stopped = sound instanceof TickableSoundInstance ticking && ticking.isStopped();
            if (stopped || !active && tick - tracked.started > 20) {
                stopped(sound, tracked, stopped ? "stopped" : "inactive");
                iterator.remove();
                continue;
            }
            if (!active) continue;
            try {
                var current = values(mc, sound);
                if (current.publishAfter(tracked.previous, tick, tracked.published)) {
                    EventJournal.record("looping_sound", json(sound, tracked, current, tracked.previous == null ? "start" : "sample"));
                    tracked.previous = current;
                    tracked.published = tick;
                }
            } catch (RuntimeException unresolved) {
                // A sound can briefly be unresolved while a resource reload is replacing its data.
            }
        }
    }
    private static JsonObject json(SoundInstance sound, Tracked tracked, LoopSoundValues value, String phase) {
        var out = new JsonObject();
        out.addProperty("instance", tracked.id);
        out.addProperty("sound", sound.getLocation().toString());
        out.addProperty("source", sound.getSource().getName());
        out.addProperty("looping", true);
        out.addProperty("phase", phase);
        if (value != null) {
            out.addProperty("volume", value.volume());
            out.addProperty("pitch", value.pitch());
            out.addProperty("source_volume", value.sourceVolume());
            out.addProperty("master_volume", value.masterVolume());
            out.addProperty("effective_volume", value.effectiveVolume());
        }
        out.addProperty("x", sound.getX()); out.addProperty("y", sound.getY()); out.addProperty("z", sound.getZ());
        return out;
    }
    private static void stopped(SoundInstance sound, Tracked tracked, String reason) {
        var data = json(sound, tracked, tracked.previous, "stop");
        data.addProperty("reason", reason);
        EventJournal.record("looping_sound", data);
    }
    static synchronized JsonObject snapshot() {
        var mc = Minecraft.getInstance();
        sample(mc);
        var sounds = new JsonArray();
        for (var entry : TRACKED.entrySet()) {
            if (!mc.getSoundManager().isActive(entry.getKey())) continue;
            try { sounds.add(json(entry.getKey(), entry.getValue(), values(mc, entry.getKey()), "current")); }
            catch (RuntimeException unresolved) {
                var error = new JsonObject();
                error.addProperty("instance", entry.getValue().id);
                error.addProperty("sound", entry.getKey().getLocation().toString());
                error.addProperty("error", "sound values unresolved");
                sounds.add(error);
            }
        }
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("limit", LIMIT);
        out.add("sounds", sounds);
        return out;
    }
    static synchronized void clear() {
        for (var entry : TRACKED.entrySet()) stopped(entry.getKey(), entry.getValue(), "disconnect");
        TRACKED.clear();
    }
}
