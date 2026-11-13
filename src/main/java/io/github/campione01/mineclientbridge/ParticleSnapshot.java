package io.github.campione01.mineclientbridge;

import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;

/**
 * [Arcane Academy fork] Live particles counted by class, so a trail or effect check reads "how
 * many of ours are alive" as numbers. NeoForge 21.1 has no particle-spawn event; this reads the
 * engine's live queues instead (opened read-only by the access transformer), which needs no mixin.
 * Particle classes are not registry IDs, but a mod's own particle classes name its effects.
 *
 * <p>Must run on the Minecraft thread.</p>
 */
final class ParticleSnapshot {
    private ParticleSnapshot() {
    }

    static JsonObject create() {
        Minecraft mc = Minecraft.getInstance();
        Map<String, Integer> counts = new TreeMap<>();
        int total = 0;
        for (Map.Entry<ParticleRenderType, Queue<Particle>> entry : mc.particleEngine.particles.entrySet()) {
            for (Particle particle : entry.getValue()) {
                counts.merge(particle.getClass().getName(), 1, Integer::sum);
                total++;
            }
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("total", total);
        JsonObject byClass = new JsonObject();
        counts.forEach(byClass::addProperty);
        obj.add("by_class", byClass);
        obj.addProperty("particles_option", mc.options.particles().get().name().toLowerCase(java.util.Locale.ROOT));
        return obj;
    }
}
