package io.github.campione01.mineclientbridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopSoundValuesTest {
    @Test void nonFiniteValuesCannotPoisonJsonSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new LoopSoundValues(Float.NaN, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LoopSoundValues(1, Float.POSITIVE_INFINITY, 1, 1));
    }
    @Test void sourceAndMasterChangesAffectPreDistanceGain() {
        var initial = new LoopSoundValues(.08F, .55F, 1, 1);
        var quieter = new LoopSoundValues(.08F, .55F, .5F, 1);
        assertEquals(.08F, initial.effectiveVolume(), 1e-6);
        assertEquals(.04F, quieter.effectiveVolume(), 1e-6);
        assertTrue(quieter.publishAfter(initial, 4, 0));
    }
    @Test void changingVolumeAndPitchAreSampledAtBoundedCadence() {
        var initial = new LoopSoundValues(.08F, .55F, 1, 1);
        var changed = new LoopSoundValues(.04F, .75F, 1, 1);
        assertTrue(initial.publishAfter(null, 0, 0));
        assertFalse(changed.publishAfter(initial, 3, 0));
        assertTrue(changed.publishAfter(initial, 4, 0));
        assertFalse(initial.publishAfter(initial, 19, 0));
        assertTrue(initial.publishAfter(initial, 20, 0));
    }
}
