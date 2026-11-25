package no.julian.lightshade.pack;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import no.julian.lightshade.render.BuiltinPacks;
import no.julian.lightshade.render.RenderPack;

/** Finds packs (built-in, folders and ZIPs in shaderpacks/) and reads their menu metadata through
 * the renderer's own bounded manifest parser. Nothing here touches the GPU. */
public final class PackManager {
    /** Settings tabs, in order. A pack's own category names are folded into these. */
    public static final List<String> CATEGORIES=List.of("Lighting","Shadows","Water","Sky","Nature","Atmosphere","Advanced","Other");
    public static final String MANIFEST="lightshade.json",ICON="pack.png";
    private static final int MAX_ICON=256*1024;
    private static final byte[] PNG={(byte)0x89,'P','N','G','\r','\n',0x1a,'\n'};

    /** One pack in the list. tag is a short badge such as "Vanilla+"; description is plain text, may be empty. */
    public record Entry(String id, String name, String tag, String description, boolean builtin, String problem) {
        public boolean usable() { return problem == null; }
    }
    public record Option(String key, String label, String category, String description,
                         double defaultValue, List<Double> values) {
        public Option { values = List.copyOf(values); }
        public double accepted(Double value) {
            return value != null && Double.isFinite(value) && values.contains(value) ? value : defaultValue;
        }
        /** Two choices, 0 and 1: shown as an on/off pill. */
        public boolean toggle() { return values.size() == 2 && values.contains(0.0) && values.contains(1.0); }
        /** Index of value among the sorted choices, or -1. */
        public int index(double value) { return sorted().indexOf(value); }
        public List<Double> sorted() { List<Double> list = new ArrayList<>(values); Collections.sort(list); return list; }
    }
    /** A named quality level from the manifest: values for some of the pack's options. */
    public record Preset(String name, Map<String,Double> values) {
        public Preset { values = Map.copyOf(values); }
    }
    public record Details(String id, String name, String tag, String description, String sha256, List<Option> options, List<Preset> presets) {
        public Details { options = List.copyOf(options); presets = List.copyOf(presets); }
        public Map<String,Double> accepted(Map<String,Double> saved) {
            Map<String,Double> values = new TreeMap<>();
            for (Option option : options) values.put(option.key(), option.accepted(saved.get(option.key())));
            return values;
        }
        public Map<String,Double> defaults() { return accepted(Map.of()); }
        /** How many options differ from the pack's own defaults. */
        public int customized(Map<String,Double> values) {
            int count = 0;
            for (Option option : options) if (Double.compare(option.accepted(values.get(option.key())), option.defaultValue()) != 0) count++;
            return count;
        }
    }

    private final Path directory;
    public PackManager(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }

    public List<Entry> available() throws IOException {
        Files.createDirectories(directory);
        List<Entry> result = new ArrayList<>();
        for (String id : BuiltinPacks.IDS) result.add(entry(id, true));
        try (var stream = Files.list(directory)) {
            for (Path path : stream.sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).limit(128).toList()) {
                if (Files.isSymbolicLink(path)) continue;
                String id = path.getFileName().toString();
                if (id.matches("[A-Za-z0-9_. -]+") && !BuiltinPacks.has(id) &&
                    (Files.isDirectory(path) || (Files.isRegularFile(path) && id.toLowerCase(Locale.ROOT).endsWith(".zip"))))
                    result.add(entry(id, false));
            }
        }
        return List.copyOf(result);
    }
    private Entry entry(String id, boolean builtin) {
        try {
            Details details = describe(id);
            return new Entry(id, details.name(), details.tag(), details.description(), builtin, null);
        } catch (Exception failure) {
            return new Entry(id, displayName(id), "", "", builtin, Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        }
    }
    /** A file name without its .zip ending. */
    static String displayName(String id) {
        return id.toLowerCase(Locale.ROOT).endsWith(".zip") ? id.substring(0, id.length() - 4) : id;
    }

    public Details describe(String id) throws Exception {
        RenderPack pack;
        try { pack = RenderPack.load(directory, id, Map.of()); }
        catch (IOException failure) {
            if (!("Missing " + MANIFEST + " at pack root").equals(failure.getMessage())) throw failure;
            throw new IOException(missingManifestMessage(id), failure);
        }
        JsonObject root = JsonParser.parseString(pack.files().get(MANIFEST)).getAsJsonObject();
        JsonObject defs = root.has("options") ? root.getAsJsonObject("options") : new JsonObject();
        List<Option> options = new ArrayList<>();
        // Manifest order is the author's order; the menu keeps it.
        for (String key : defs.keySet()) {
            if (!pack.options().containsKey(key)) continue;
            double defaultValue = pack.options().get(key);
            JsonElement raw = defs.get(key);
            JsonObject def = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
            String label = str(def, "label", humanize(key));
            String category = category(str(def, "category", inferCategory(key)));
            String description = str(def, "description", "");
            List<Double> values = new ArrayList<>();
            if (def.has("values")) for (JsonElement value : def.getAsJsonArray("values")) {
                double n = value.getAsDouble();
                if (!Double.isFinite(n) || Math.abs(n) > 1000000) throw new IOException("Invalid choice for " + key);
                if (!values.contains(n)) values.add(n);
            }
            if (!values.contains(defaultValue)) values.add(defaultValue);
            options.add(new Option(key, label, category, description, defaultValue, values));
        }
        List<Preset> presets = new ArrayList<>();
        if (root.has("presets") && root.get("presets").isJsonArray()) for (JsonElement raw : root.getAsJsonArray("presets")) {
            JsonObject preset = raw.getAsJsonObject();
            Map<String,Double> values = new TreeMap<>();
            for (var entry : preset.getAsJsonObject("values").entrySet()) {
                Option option = options.stream().filter(o -> o.key().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> new IOException("Preset names unknown option " + entry.getKey()));
                double value = entry.getValue().getAsDouble();
                if (!option.values().contains(value)) throw new IOException("Preset value not offered: " + entry.getKey() + "=" + value);
                values.put(entry.getKey(), value);
            }
            presets.add(new Preset(str(preset, "name", "Preset " + (presets.size() + 1)), values));
        }
        String name = str(root, "name", displayName(id));
        String tag = str(root, "tag", "");
        if (tag.length() > 24) tag = tag.substring(0, 24);
        return new Details(id, name, tag, str(root, "description", ""), pack.sha256(), options, presets);
    }

    /** The pack's icon as PNG bytes (pack.png at its root), or null when it has none or it is not a PNG. */
    public byte[] icon(String id) {
        try {
            byte[] bytes;
            if (BuiltinPacks.has(id)) bytes = BuiltinPacks.icon(id);
            else {
                if (!id.matches("[A-Za-z0-9_. -]+") || id.equals(".") || id.equals("..")) return null;
                Path path = directory.resolve(id).normalize();
                if (!path.startsWith(directory) || Files.isSymbolicLink(path)) return null;
                if (Files.isDirectory(path)) {
                    Path icon = path.resolve(ICON);
                    if (!Files.isRegularFile(icon) || Files.isSymbolicLink(icon)) return null;
                    try (InputStream input = Files.newInputStream(icon)) { bytes = input.readNBytes(MAX_ICON + 1); }
                } else {
                    try (ZipFile archive = new ZipFile(path.toFile())) {
                        var entry = archive.getEntry(ICON);
                        if (entry == null || entry.isDirectory()) return null;
                        try (InputStream input = archive.getInputStream(entry)) { bytes = input.readNBytes(MAX_ICON + 1); }
                    }
                }
            }
            if (bytes == null || bytes.length > MAX_ICON || bytes.length < PNG.length) return null;
            for (int i = 0; i < PNG.length; i++) if (bytes[i] != PNG[i]) return null;
            return bytes;
        } catch (IOException failure) {
            return null;
        }
    }

    /** Diagnostic only after the core has validated this local path and found no manifest. */
    private String missingManifestMessage(String id) throws IOException {
        Path path = directory.resolve(id);
        List<String> foreign = List.of("sulkan.json", "shaders/shaders.properties", "shaders/gbuffers_terrain.vsh");
        boolean incompatible;
        if (Files.isDirectory(path)) {
            incompatible = foreign.stream().anyMatch(name -> Files.isRegularFile(path.resolve(name)));
        } else {
            try (ZipFile archive = new ZipFile(path.toFile())) {
                incompatible = foreign.stream().anyMatch(name -> archive.getEntry(name) != null);
            }
        }
        if (incompatible) return "This is an Iris/OptiFine or Sulkan pack. Lightshade needs a pack made for Lightshade (with " + MANIFEST + ").";
        return Files.isDirectory(path) ? "A Lightshade pack needs " + MANIFEST + " at the folder root."
            : "A Lightshade pack needs " + MANIFEST + " at the ZIP root. If it sits inside an extra folder, re-zip its contents.";
    }
    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }
    private static String humanize(String key) {
        String value = key.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
    static String inferCategory(String key) {
        if (key.startsWith("WATER")) return "Water";
        if (key.startsWith("SHADOW")) return "Shadows";
        if (key.startsWith("CLOUD") || key.startsWith("SUN") || key.startsWith("MOON") || key.startsWith("SKY")) return "Sky";
        if (key.startsWith("WAVING") || key.startsWith("FOLIAGE")) return "Nature";
        if (key.equals("EXPOSURE") || key.startsWith("COLOR") || key.startsWith("BLOOM") || key.equals("SATURATION")
            || key.equals("CONTRAST") || key.equals("VIBRANCE") || key.equals("VIGNETTE") || key.equals("ATMOSPHERE")) return "Atmosphere";
        if (key.startsWith("LIGHT") || key.startsWith("TORCH") || key.contains("OCCLUSION")) return "Lighting";
        return "Other";
    }
    static String category(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.contains("advanced")) return "Advanced";
        if (value.contains("shadow")) return "Shadows";
        if (value.contains("water")) return "Water";
        if (value.contains("atmosphere") || value.contains("mood") || value.contains("color") || value.contains("colour")
            || value.contains("tone") || value.contains("image") || value.contains("grade")) return "Atmosphere";
        if (value.contains("sky") || value.contains("cloud") || value.contains("moon")) return "Sky";
        if (value.contains("nature") || value.contains("waving") || value.contains("plant")) return "Nature";
        if (value.contains("light")) return "Lighting";
        return "Other";
    }
}
