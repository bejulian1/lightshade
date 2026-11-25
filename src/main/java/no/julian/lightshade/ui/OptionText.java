package no.julian.lightshade.ui;

import java.util.*;
import java.util.function.BiFunction;
import no.julian.lightshade.pack.PackManager.Option;

/** How option values read in the menu. Pure text: the translator is passed in, so this is testable offline. */
public final class OptionText {
    /** Options whose choices are quality steps rather than amounts. */
    static final Set<String> LEVELS = Set.of("WATER_REFLECTION_QUALITY", "CLOUD_QUALITY", "GODRAYS", "VOLUMETRIC_LIGHT", "AMBIENT_OCCLUSION");
    private final BiFunction<String,String,String> tr;
    public OptionText(BiFunction<String,String,String> translator) { tr = translator; }

    public String name(Option option) { return tr.apply("lightshade.option." + option.key(), option.label()); }
    public String help(Option option) { return tr.apply("lightshade.option." + option.key() + ".desc", option.description()); }
    public String category(String category) { return tr.apply("lightshade.category." + category.toLowerCase(Locale.ROOT), category); }
    public String preset(String name) { return tr.apply("lightshade.preset." + name.toLowerCase(Locale.ROOT), name); }

    public String value(Option option, double value) {
        String key = option.key();
        if (option.toggle()) return tr.apply(value > 0 ? "lightshade.level.on" : "lightshade.level.off", value > 0 ? "On" : "Off");
        if (LEVELS.contains(key)) {
            List<Double> sorted = option.sorted();
            int index = sorted.indexOf(value), count = sorted.size();
            String[] names = count == 2 ? new String[]{"off", "on"} : count == 3 ? new String[]{"off", "low", "high"}
                : count == 4 ? new String[]{"off", "low", "medium", "high"} : new String[]{"off", "low", "medium", "high", "ultra"};
            if (index >= 0 && index < names.length)
                return tr.apply("lightshade.level." + names[index], Character.toUpperCase(names[index].charAt(0)) + names[index].substring(1));
        }
        if (key.equals("SHADOW_RESOLUTION")) return String.format(Locale.ROOT, "%.0f px", value);
        if (key.equals("SHADOW_DISTANCE")) return String.format(tr.apply("lightshade.menu.blocks", "%s blocks"), String.format(Locale.ROOT, "%.0f", value));
        if (key.equals("SHADOW_CASCADES")) return String.format(Locale.ROOT, "%.0f", value);
        if (key.equals("SUN_TILT")) return String.format(Locale.ROOT, "%.0f°", value);
        if (key.equals("SUN_SIZE") || key.equals("MOON_SIZE")) return "×" + trim(value);
        return String.format(Locale.ROOT, "%.0f%%", value * 100);
    }
    static String trim(double value) {
        return value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.2f", value).replaceAll("0$", "");
    }
}
