package no.julian.lightshade.config;

import java.util.*;
import no.julian.lightshade.pack.PackManager.Option;

/** Menu-only draft: stepping, presets and Cancel never touch the running renderer or the saved file. */
public final class SettingsDraft {
    private final List<Option> options;
    private final Map<String,Double> values;
    public SettingsDraft(List<Option> options, Map<String,Double> saved) {
        this.options = List.copyOf(options);
        values = new TreeMap<>();
        for (Option option : options) values.put(option.key(), option.accepted(saved.get(option.key())));
    }
    public List<Option> options() { return options; }
    public Map<String,Double> values() { return Map.copyOf(values); }
    public double value(String key) { return values.get(key); }
    /** One choice up (direction 1) or down (-1) in ascending order. wrap: past the end comes the start. */
    public void step(Option option, int direction, boolean wrap) {
        List<Double> sorted = option.sorted();
        int index = sorted.indexOf(values.get(option.key())), next = index + direction;
        if (wrap) next = Math.floorMod(next, sorted.size());
        else next = Math.clamp(next, 0, sorted.size() - 1);
        values.put(option.key(), sorted.get(next));
    }
    public void cycle(Option option, int direction) { step(option, direction, true); }
    /** Picks the choice at a fraction 0..1 along the option's range of choices. */
    public void setFraction(Option option, double fraction) {
        List<Double> sorted = option.sorted();
        int index = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * (sorted.size() - 1));
        values.put(option.key(), sorted.get(index));
    }
    /** Position 0..1 of the current value among the option's choices. */
    public double fraction(Option option) {
        List<Double> sorted = option.sorted();
        return sorted.size() < 2 ? 1.0 : (double) sorted.indexOf(values.get(option.key())) / (sorted.size() - 1);
    }
    public boolean isDefault(Option option) { return Double.compare(values.get(option.key()), option.defaultValue()) == 0; }
    public int customized() { int count = 0; for (Option option : options) if (!isDefault(option)) count++; return count; }
    /** Validate the entire edit before changing any draft value. Never persists. */
    public void setValues(Map<String,Double> changes) {
        Map<String,Option> declared = new HashMap<>();
        options.forEach(option -> declared.put(option.key(), option));
        for (var entry : changes.entrySet()) {
            Option option = declared.get(entry.getKey());
            Double value = entry.getValue();
            if (option == null || value == null || !Double.isFinite(value) || !option.values().contains(value))
                throw new IllegalArgumentException("Unsupported draft value for " + entry.getKey());
        }
        values.putAll(changes);
    }
    public void reset() { for (Option option : options) values.put(option.key(), option.defaultValue()); }
}
