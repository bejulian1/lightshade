package no.julian.lightshade.config;

import java.util.*;
import no.julian.lightshade.pack.PackManager.Preset;

/** A pack's quality levels (its manifest "presets"). They set only performance options; sun, moon,
 * colours and the rest of the look are never overwritten. */
public final class QualityPresets {
    private QualityPresets() { }
    public static Optional<Preset> matching(List<Preset> available, Map<String,Double> draft) {
        return available.stream().filter(preset -> preset.values().entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(draft.get(entry.getKey())))).findFirst();
    }
    public static Preset next(List<Preset> available, Map<String,Double> draft, int direction) {
        if (available.isEmpty()) throw new IllegalArgumentException("This pack has no quality presets");
        int index = matching(available, draft).map(available::indexOf).orElse(-1);
        if (index < 0) return direction < 0 ? available.getLast() : available.getFirst();
        return available.get(Math.floorMod(index + (direction < 0 ? -1 : 1), available.size()));
    }
    public static void select(SettingsDraft draft, Preset preset) { draft.setValues(preset.values()); }
}
