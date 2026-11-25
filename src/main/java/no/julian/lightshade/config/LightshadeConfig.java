package no.julian.lightshade.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import no.julian.lightshade.render.BuiltinPacks;

/** Local user choices. A successful renderer transaction is persisted atomically. */
public final class LightshadeConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private State state = State.defaults();
    private String loadError;
    public record State(boolean enabled, String selectedPack, Map<String, Map<String, Double>> packs) {
        public State {
            Objects.requireNonNull(selectedPack);
            Map<String, Map<String, Double>> frozen = new TreeMap<>();
            packs.forEach((key, value) -> frozen.put(key, Map.copyOf(value)));
            packs = Collections.unmodifiableMap(frozen);
        }
        public static State defaults() { return new State(true, BuiltinPacks.DEFAULT, Map.of()); }
        public Map<String,Double> options(String pack) { return packs.getOrDefault(pack, Map.of()); }
        public State selected(boolean enabled, String id, Map<String,Double> values) {
            Map<String,Map<String,Double>> next = new TreeMap<>(packs);
            next.put(id, Map.copyOf(values));
            return new State(enabled, id, next);
        }
    }
    public LightshadeConfig(Path file) { this.file = file; }
    public synchronized State state() { return state; }
    public synchronized String loadError() { return loadError; }
    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 262144) throw new IOException("Configuration exceeds 256 KiB");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("version") && root.get("version").getAsInt() != 1)
                throw new IOException("Unsupported configuration version");
            boolean enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
            String selected = root.has("selectedPack") ? root.get("selectedPack").getAsString() : BuiltinPacks.DEFAULT;
            if (selected.isBlank() || selected.length() > 240) throw new IOException("Invalid selected pack name");
            Map<String,Map<String,Double>> packs = new TreeMap<>();
            if (root.has("packs")) {
                for (var pack : root.getAsJsonObject("packs").entrySet()) {
                    Map<String,Double> options = new TreeMap<>();
                    for (var value : pack.getValue().getAsJsonObject().entrySet()) {
                        double number = value.getValue().getAsDouble();
                        if (value.getKey().matches("[A-Z][A-Z0-9_]{0,63}") && Double.isFinite(number) && Math.abs(number) <= 1000000)
                            options.put(value.getKey(), number);
                    }
                    packs.put(pack.getKey(), options);
                }
            }
            state = new State(enabled, selected, packs);
            loadError = null;
        } catch (Exception failure) {
            loadError = "Could not read Lightshade settings: " + failure.getMessage();
            state = State.defaults();
            // The original file remains untouched until an explicit Apply.
        }
    }
    public synchronized void save(State next) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("enabled", next.enabled());
        root.addProperty("selectedPack", next.selectedPack());
        root.add("packs", JSON.toJsonTree(next.packs()));
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "lightshade-", ".json.tmp");
        try {
            Files.writeString(temp, JSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            state = next;
            loadError = null;
        } finally { Files.deleteIfExists(temp); }
    }
}
