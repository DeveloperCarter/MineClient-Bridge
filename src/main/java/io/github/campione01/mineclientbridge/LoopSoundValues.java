package io.github.campione01.mineclientbridge;

/** [Arcane Academy fork] Read-only sampled values and publication policy, free of Minecraft state. */
record LoopSoundValues(float volume, float pitch, float sourceVolume, float masterVolume) {
    LoopSoundValues {
        if (!Float.isFinite(volume) || !Float.isFinite(pitch) || !Float.isFinite(sourceVolume) || !Float.isFinite(masterVolume)) {
            throw new IllegalArgumentException("Sound values must be finite");
        }
    }
    float effectiveVolume() { return Math.clamp(volume * sourceVolume, 0, 1) * masterVolume; }
    boolean publishAfter(LoopSoundValues previous, long now, long last) {
        return previous == null || now - last >= 20 || now - last >= 4 && !equals(previous);
    }
}
