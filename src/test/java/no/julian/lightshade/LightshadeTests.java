package no.julian.lightshade;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import no.julian.lightshade.config.LightshadeConfig;
import no.julian.lightshade.config.QualityPresets;
import no.julian.lightshade.config.SettingsDraft;
import no.julian.lightshade.pack.PackManager;
import no.julian.lightshade.pack.PackManager.*;
import no.julian.lightshade.render.RenderPack;
import no.julian.lightshade.ui.OptionText;
import no.julian.lightshade.ui.PackSelection;

/** Offline checks of config, presets, pack loading, menu state and option text. No Minecraft client,
 * GPU or user config is touched; everything works in a temporary folder. Exit code 0 only when all pass. */
public final class LightshadeTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("lightshade-tests-");
        run("config", () -> config(temp.resolve("config")));
        run("built-in packs", LightshadeTests::builtins);
        run("pack folder", () -> packFolder(temp.resolve("packs")));
        run("presets", LightshadeTests::presets);
        run("settings draft", LightshadeTests::draft);
        run("pack screen state", () -> selection(temp.resolve("selection")));
        run("option text", LightshadeTests::optionText);
        run("conflict guard", LightshadeTests::conflicts);
        System.out.println(passed + " checks passed, " + failed + " failed");
        failures.forEach(f -> System.out.println("FAIL " + f));
        System.exit(failed == 0 ? 0 : 1);
    }
    interface Body { void run() throws Exception; }
    private static void run(String group, Body body) {
        try { body.run(); }
        catch (Throwable error) { failed++; failures.add(group + ": unexpected " + error); }
    }
    private static void check(boolean value, String message) {
        if (value) passed++; else { failed++; failures.add(message); }
    }
    private static void zip(Path path, Map<String,byte[]> entries) throws Exception {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) { output.putNextEntry(new ZipEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry(); }
        }
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static final byte[] PNG = {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    static void config(Path dir) throws Exception {
        Path file = dir.resolve("lightshade.json");
        LightshadeConfig config = new LightshadeConfig(file);
        config.load();
        check(config.state().enabled(), "first launch turns shaders on");
        check(config.state().selectedPack().equals("daylight"), "first launch picks Lightshade Daylight");
        check(!Files.exists(file), "reading defaults never writes a file");
        Map<String,Double> values = new HashMap<>(Map.of("SUN_SIZE", 1.5, "LIGHT_NIGHT", .5));
        LightshadeConfig.State changed = config.state().selected(true, "daylight", values);
        values.put("SUN_SIZE", 999.0);
        check(changed.options("daylight").get("SUN_SIZE") == 1.5, "state copies the caller's options");
        config.save(changed);
        LightshadeConfig reopened = new LightshadeConfig(file);
        reopened.load();
        check(reopened.state().equals(changed), "saved state round-trips exactly");
        check(reopened.loadError() == null, "a valid file loads without error");
        try (var files = Files.list(dir)) { check(files.count() == 1, "atomic save leaves no temporary files"); }
        config.save(changed.selected(false, "custom.zip", Map.of("EXPOSURE", .9)));
        reopened.load();
        check(!reopened.state().enabled(), "shaders-off round-trips");
        check(reopened.state().options("daylight").get("SUN_SIZE") == 1.5, "switching packs keeps the other pack's settings");
        check(reopened.state().options("custom.zip").get("EXPOSURE") == .9, "each pack keeps its own settings");
        String future = "{\"version\":99,\"selectedPack\":\"aurora\"}";
        Files.writeString(file, future);
        reopened.load();
        check(reopened.loadError() != null, "a future file version is refused");
        check(Files.readString(file).equals(future), "a refused file is left untouched");
        check(reopened.state().equals(LightshadeConfig.State.defaults()), "a refused file falls back to defaults");
        Files.writeString(file, "{\"version\":1,\"packs\":{\"daylight\":{\"SUN_SIZE\":1.25,\"BAD\":\"NaN\",\"INVALID KEY\":2}}}");
        reopened.load();
        check(reopened.state().options("daylight").equals(Map.of("SUN_SIZE", 1.25)), "non-finite values and bad keys are dropped");
        Files.writeString(file, "not json at all");
        reopened.load();
        check(reopened.loadError() != null && reopened.state().equals(LightshadeConfig.State.defaults()), "a broken file falls back to defaults");
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "fixed");
        LightshadeConfig blocked = new LightshadeConfig(blocker.resolve("lightshade.json"));
        boolean threw = false;
        try { blocked.save(changed); } catch (java.io.IOException expected) { threw = true; }
        check(threw, "saving into an impossible path fails");
        check(blocked.state().equals(LightshadeConfig.State.defaults()), "a failed save does not change the committed state");
        check(Files.readString(blocker).equals("fixed"), "a failed save leaves other files alone");
    }

    static void builtins() throws Exception {
        PackManager manager = new PackManager(Files.createTempDirectory("lightshade-builtins-"));
        List<Entry> entries = manager.available();
        check(entries.stream().map(Entry::id).toList().equals(List.of("daylight", "aurora")), "an empty folder lists both built-in packs, Daylight first");
        check(entries.stream().map(Entry::name).toList().equals(List.of("Lightshade Daylight", "Lightshade Aurora")), "built-in names come from their manifests");
        check(entries.stream().allMatch(e -> e.builtin() && e.usable()), "built-in packs are usable");
        check(entries.get(0).tag().equals("Vanilla+") && entries.get(1).tag().equals("Cinematic"), "built-in packs carry their badges");
        check(entries.stream().allMatch(e -> e.description().length() > 40), "built-in packs carry descriptions");
        for (String id : List.of("daylight", "aurora")) {
            byte[] icon = manager.icon(id);
            check(icon != null && icon.length > 100, id + " has a pack icon");
            int width = icon == null ? 0 : ((icon[16] & 255) << 24) | ((icon[17] & 255) << 16) | ((icon[18] & 255) << 8) | (icon[19] & 255);
            check(width == 64, id + " icon is 64 pixels wide");
            Details details = manager.describe(id);
            check(details.presets().stream().map(Preset::name).toList().equals(List.of("Low", "Medium", "High", "Ultra")), id + " offers Low, Medium, High and Ultra");
            check(details.options().stream().allMatch(o -> PackManager.CATEGORIES.contains(o.category()) && !o.category().equals("Other")), id + " puts every option on a named tab");
            Set<String> tabs = new LinkedHashSet<>();
            details.options().forEach(o -> tabs.add(o.category()));
            check(tabs.containsAll(List.of("Lighting", "Shadows", "Water", "Sky", "Nature", "Atmosphere", "Advanced")), id + " fills all seven tabs");
            check(QualityPresets.matching(details.presets(), details.defaults()).map(Preset::name).orElse("").equals("High"), id + " defaults are the High preset");
            for (Preset preset : details.presets()) {
                Map<String,Double> merged = new TreeMap<>(details.defaults());
                merged.putAll(preset.values());
                check(details.accepted(merged).equals(merged), id + " preset " + preset.name() + " only uses offered values");
                RenderPack pack = RenderPack.load(manager.directory(), id, merged);
                check(pack.options().keySet().containsAll(preset.values().keySet()), id + " preset " + preset.name() + " loads in the renderer parser");
            }
            for (Option option : details.options()) {
                check(option.values().contains(option.defaultValue()), id + " " + option.key() + " default is a choice");
                check(option.sorted().size() >= 2, id + " " + option.key() + " has at least two choices");
            }
            check(details.customized(details.defaults()) == 0, id + " defaults count as nothing customised");
        }
        Details daylight = manager.describe("daylight"), aurora = manager.describe("aurora");
        check(aurora.options().size() > daylight.options().size(), "Aurora offers more than Daylight");
        check(daylight.options().stream().noneMatch(o -> o.key().equals("SHADOW_CASCADES")), "Daylight keeps a single shadow map");
        check(aurora.options().stream().anyMatch(o -> o.key().equals("SKY_AURORA") && o.defaultValue() > 0), "Aurora shows the northern lights by default");
    }

    static void packFolder(Path dir) throws Exception {
        PackManager manager = new PackManager(dir);
        Files.createDirectories(dir);
        String manifest = "{\"version\":1,\"name\":\"Mine\",\"tag\":\"Test\",\"description\":\"A test pack\",\"terrain\":{\"vertex\":\"shaders/t.vsh\",\"fragment\":\"shaders/t.fsh\"},"
            + "\"options\":{\"EXPOSURE\":{\"default\":1.0,\"values\":[0.5,1.0,1.5]},\"SHADOWS\":{\"default\":1.0,\"values\":[0.0,1.0]}},"
            + "\"presets\":[{\"name\":\"Low\",\"values\":{\"SHADOWS\":0.0}}],"
            + "\"passes\":[{\"name\":\"display\",\"fragment\":\"shaders/d.fsh\",\"inputs\":{\"Scene\":\"$scene\"},\"output\":\"$screen\"}]}";
        byte[] icon = new byte[200];
        System.arraycopy(PNG, 0, icon, 0, PNG.length);
        zip(dir.resolve("Mine.zip"), Map.of("lightshade.json", utf8(manifest), "shaders/t.vsh", utf8("#version 450\n"),
            "shaders/t.fsh", utf8("#version 450\n"), "shaders/d.fsh", utf8("#version 450\n"), "pack.png", icon));
        zip(dir.resolve("Nested.zip"), Map.of("Nested/lightshade.json", utf8(manifest)));
        zip(dir.resolve("Iris.zip"), Map.of("shaders/shaders.properties", utf8("x=1")));
        zip(dir.resolve("BadIcon.zip"), Map.of("lightshade.json", utf8(manifest), "shaders/t.vsh", utf8("#version 450\n"),
            "shaders/t.fsh", utf8("#version 450\n"), "shaders/d.fsh", utf8("#version 450\n"), "pack.png", utf8("not a png")));
        Files.createDirectories(dir.resolve("Folder pack/shaders"));
        Files.writeString(dir.resolve("Folder pack/lightshade.json"), manifest);
        for (String name : List.of("t.vsh", "t.fsh", "d.fsh")) Files.writeString(dir.resolve("Folder pack/shaders/" + name), "#version 450\n");
        Files.write(dir.resolve("Folder pack/pack.png"), icon);
        Files.writeString(dir.resolve("notes.txt"), "ignored");

        List<Entry> entries = manager.available();
        List<String> ids = entries.stream().map(Entry::id).toList();
        check(ids.equals(List.of("daylight", "aurora", "BadIcon.zip", "Folder pack", "Iris.zip", "Mine.zip", "Nested.zip")), "folder lists built-ins first, then ZIPs and folders by name: " + ids);
        Entry mine = entries.stream().filter(e -> e.id().equals("Mine.zip")).findFirst().orElseThrow();
        check(mine.usable() && mine.name().equals("Mine") && mine.tag().equals("Test") && mine.description().equals("A test pack"), "a ZIP's name, tag and description are read");
        check(manager.icon("Mine.zip") != null, "a ZIP's pack.png is found");
        check(manager.icon("Folder pack") != null, "a folder's pack.png is found");
        check(manager.icon("BadIcon.zip") == null, "a pack.png that is not a PNG is refused");
        check(manager.icon("../escape") == null && manager.icon("aurora/../../x") == null, "icon lookups cannot leave the pack folder");
        Entry nested = entries.stream().filter(e -> e.id().equals("Nested.zip")).findFirst().orElseThrow();
        check(!nested.usable() && nested.problem().contains("ZIP root"), "a ZIP with its pack in a subfolder explains the problem");
        check(nested.name().equals("Nested"), "a broken ZIP shows its file name without .zip");
        Entry iris = entries.stream().filter(e -> e.id().equals("Iris.zip")).findFirst().orElseThrow();
        check(!iris.usable() && iris.problem().contains("Iris"), "an Iris/OptiFine pack is recognised and explained");
        Details details = manager.describe("Mine.zip");
        check(details.options().size() == 2 && details.presets().size() == 1, "options and presets are read from a ZIP");
        check(details.options().stream().filter(o -> o.key().equals("SHADOWS")).findFirst().orElseThrow().toggle(), "a 0/1 option is a switch");
        check(details.options().stream().filter(o -> o.key().equals("SHADOWS")).findFirst().orElseThrow().category().equals("Shadows"), "SHADOWS goes on the Shadows tab");
        check(details.options().stream().filter(o -> o.key().equals("EXPOSURE")).findFirst().orElseThrow().category().equals("Atmosphere"), "EXPOSURE goes on the Atmosphere tab");
        boolean refused = false;
        try { RenderPack.load(dir, "Mine.zip", Map.of("EXPOSURE", 0.7)); } catch (java.io.IOException expected) { refused = true; }
        check(refused, "the renderer refuses a value the manifest does not offer");
        refused = false;
        try { RenderPack.load(dir, "../outside", Map.of()); } catch (java.io.IOException expected) { refused = true; }
        check(refused, "pack names cannot escape the pack folder");
        String badPreset = manifest.replace("{\"SHADOWS\":0.0}", "{\"SHADOWS\":0.5}");
        Files.writeString(dir.resolve("Folder pack/lightshade.json"), badPreset);
        refused = false;
        try { manager.describe("Folder pack"); } catch (Exception expected) { refused = true; }
        check(refused, "a preset with a value the option does not offer is refused");
    }

    static void presets() {
        Preset low = new Preset("Low", Map.of("A", 0.0)), high = new Preset("High", Map.of("A", 1.0)), ultra = new Preset("Ultra", Map.of("A", 1.0, "B", 2.0));
        List<Preset> list = List.of(low, high, ultra);
        check(QualityPresets.matching(list, Map.of("A", 0.0, "B", 1.0)).orElseThrow() == low, "a draft matching Low shows Low");
        check(QualityPresets.matching(list, Map.of("A", 1.0, "B", 1.0)).orElseThrow() == high, "the first matching preset wins");
        check(QualityPresets.matching(list, Map.of("A", 0.5)).isEmpty(), "a draft matching no preset is custom");
        check(QualityPresets.next(list, Map.of("A", 0.0), 1) == high, "next steps forward");
        check(QualityPresets.next(list, Map.of("A", 0.0), -1) == ultra, "previous wraps around");
        check(QualityPresets.next(list, Map.of("A", 0.5), 1) == low && QualityPresets.next(list, Map.of("A", 0.5), -1) == ultra, "from custom, next starts at an end");
    }

    static Option option(String key, double def, Double... values) { return new Option(key, key, "Lighting", "", def, List.of(values)); }

    static void draft() {
        Option amount = option("EXPOSURE", 1.0, 1.2, 0.8, 1.0), toggle = option("SHADOWS", 1.0, 0.0, 1.0);
        SettingsDraft draft = new SettingsDraft(List.of(amount, toggle), Map.of("EXPOSURE", 0.8, "SHADOWS", 7.0));
        check(draft.value("EXPOSURE") == 0.8 && draft.value("SHADOWS") == 1.0, "saved values are taken when offered, defaults otherwise");
        check(amount.sorted().equals(List.of(0.8, 1.0, 1.2)), "choices are stepped in ascending order whatever the manifest order");
        draft.step(amount, 1, false); check(draft.value("EXPOSURE") == 1.0, "+ steps up");
        draft.step(amount, 1, false); draft.step(amount, 1, false); check(draft.value("EXPOSURE") == 1.2, "+ stops at the top");
        draft.step(amount, 1, true); check(draft.value("EXPOSURE") == 0.8, "click stepping wraps past the top");
        draft.step(amount, -1, true); check(draft.value("EXPOSURE") == 1.2, "right-click stepping wraps past the bottom");
        draft.setFraction(amount, 0.0); check(draft.value("EXPOSURE") == 0.8, "the left end of the bar is the lowest choice");
        draft.setFraction(amount, 0.49); check(draft.value("EXPOSURE") == 1.0, "the middle of the bar is the middle choice");
        draft.setFraction(amount, 7.0); check(draft.value("EXPOSURE") == 1.2, "dragging past the end clamps");
        check(draft.fraction(amount) == 1.0, "the bar is full at the top choice");
        draft.cycle(toggle, 1); check(draft.value("SHADOWS") == 0.0, "a switch flips");
        check(draft.customized() == 2 && !draft.isDefault(amount), "changed options are counted and marked");
        boolean threw = false;
        try { draft.setValues(Map.of("EXPOSURE", 1.0, "SHADOWS", 0.5)); } catch (IllegalArgumentException expected) { threw = true; }
        check(threw && draft.value("EXPOSURE") == 1.2, "a bad batch changes nothing");
        threw = false;
        try { draft.setValues(Map.of("UNKNOWN", 1.0)); } catch (IllegalArgumentException expected) { threw = true; }
        check(threw, "an unknown option is refused");
        draft.reset(); check(draft.customized() == 0 && draft.value("SHADOWS") == 1.0, "Defaults restores every option");
        check(draft.values().size() == 2, "the draft holds every option");
    }

    static void selection(Path dir) throws Exception {
        PackManager manager = new PackManager(dir);
        Details daylight = manager.describe("daylight"), aurora = manager.describe("aurora");
        LightshadeConfig.State saved = LightshadeConfig.State.defaults();
        PackSelection screen = new PackSelection(saved);
        screen.setPacks(List.of(
            new Entry("daylight", "Lightshade Daylight", "Vanilla+", "", true, null),
            new Entry("aurora", "Lightshade Aurora", "Cinematic", "", true, null),
            new Entry("broken.zip", "broken", "", "", false, "Missing lightshade.json")));
        check("daylight".equals(screen.active()) && screen.enabled(), "the saved pack starts in the active column");
        check(screen.available().stream().map(Entry::id).toList().equals(List.of("aurora", "broken.zip")), "the active pack leaves the available column");
        check(!screen.dirty(daylight), "nothing changed is not dirty");
        check(!screen.activate("broken.zip") && "daylight".equals(screen.active()), "a broken pack cannot be made active");
        check(!screen.activate("missing") && "daylight".equals(screen.active()), "an unknown pack cannot be made active");
        check(screen.activate("aurora") && screen.dirty(aurora), "moving another pack in is a change to apply");
        check(screen.available().stream().map(Entry::id).toList().equals(List.of("daylight", "broken.zip")), "the previous active pack goes back to available");
        screen.deactivate();
        check(!screen.enabled() && screen.dirty(null) && screen.target().equals("daylight"), "◀ turns shaders off; Apply keeps the saved pack's settings");
        screen.activate("daylight");
        check(!screen.dirty(daylight), "moving the saved pack back undoes the change");
        Preset ultra = daylight.presets().stream().filter(p -> p.name().equals("Ultra")).findFirst().orElseThrow();
        screen.selectPreset(daylight, ultra);
        check(screen.preset(daylight).orElseThrow().name().equals("Ultra"), "a quick preset pill selects that preset");
        check(screen.dirty(daylight) && screen.optionsChanged(daylight), "a preset is a change to apply");
        check(screen.customized(daylight) > 0, "the settings button counts options moved off the defaults");
        check(screen.draft(daylight).get("SHADOW_RESOLUTION") == 3072.0, "Ultra sets Daylight' shadow detail to 3072");
        Map<String,Double> before = Map.copyOf(screen.draft(daylight));
        screen.activate("aurora");
        screen.activate("daylight");
        check(screen.draft(daylight).equals(before), "drafts are kept per pack while switching");
        LightshadeConfig.State now = saved.selected(true, "daylight", screen.draft(daylight));
        screen.committed(now);
        check(!screen.dirty(daylight) && screen.preset(daylight).orElseThrow().name().equals("Ultra"), "after Apply the new values are the saved ones");
        PackSelection vanished = new PackSelection(saved.selected(true, "gone.zip", Map.of()));
        vanished.setPacks(List.of(new Entry("daylight", "Lightshade Daylight", "", "", true, null)));
        check(vanished.activeEntry().map(e -> !e.usable()).orElse(false), "a saved pack that vanished still shows, marked as a problem");
        PackSelection off = new PackSelection(new LightshadeConfig.State(false, "aurora", Map.of()));
        off.setPacks(List.of(new Entry("daylight", "", "", "", true, null), new Entry("aurora", "", "", "", true, null)));
        check(!off.enabled() && off.available().size() == 2 && !off.dirty(null), "with shaders off every pack is available");
    }

    static void optionText() {
        Map<String,String> nb = Map.of("lightshade.level.on", "På", "lightshade.level.off", "Av", "lightshade.level.low", "Lav",
            "lightshade.level.medium", "Middels", "lightshade.level.high", "Høy", "lightshade.menu.blocks", "%s blokker",
            "lightshade.category.atmosphere", "Stemning", "lightshade.preset.ultra", "Ultra");
        OptionText text = new OptionText((key, fallback) -> nb.getOrDefault(key, fallback));
        check(text.value(option("SHADOWS", 1, 0.0, 1.0), 1.0).equals("På") && text.value(option("SHADOWS", 1, 0.0, 1.0), 0.0).equals("Av"), "switches read På/Av");
        Option quality = option("CLOUD_QUALITY", 2, 0.0, 1.0, 2.0, 3.0);
        check(text.value(quality, 0.0).equals("Av") && text.value(quality, 2.0).equals("Middels") && text.value(quality, 3.0).equals("Høy"), "quality steps read as levels");
        check(text.value(option("SHADOW_RESOLUTION", 2048, 1024.0, 2048.0), 2048.0).equals("2048 px"), "shadow detail reads in pixels");
        check(text.value(option("SHADOW_DISTANCE", 96, 64.0, 96.0), 96.0).equals("96 blokker"), "shadow range reads in blocks");
        check(text.value(option("SUN_TILT", 15, 0.0, 15.0), 15.0).equals("15°"), "sun tilt reads in degrees");
        check(text.value(option("SUN_SIZE", 1, 1.0, 1.25), 1.25).equals("×1.25") && text.value(option("SUN_SIZE", 1, 1.0, 1.5), 1.5).equals("×1.5"), "sizes read as factors");
        check(text.value(option("EXPOSURE", 1, 0.9, 1.0), 0.9).equals("90%"), "amounts read as percent");
        check(text.category("Atmosphere").equals("Stemning") && text.category("Water").equals("Water"), "tabs translate, with the English name as fallback");
        check(text.preset("Ultra").equals("Ultra"), "preset names translate");
        check(text.name(option("EXPOSURE", 1, 1.0, 2.0)).equals("EXPOSURE"), "an unknown option falls back to its manifest label");
    }

    static void conflicts() {
        check(LightshadeClient.conflict(List.of("minecraft", "fabric-api", "sodium", "lightshade")) == null, "a normal mod list has no conflict");
        check("sulkan".equals(LightshadeClient.conflict(List.of("lightshade", "sulkan"))), "Sulkan is a conflict");
        check("iris".equals(LightshadeClient.conflict(List.of("iris", "lightshade"))), "Iris is a conflict");
        check("old_shader_loader".equals(LightshadeClient.conflict(List.of("lightshade", "old_shader_loader"))), "any other *_shader_loader is a conflict");
    }
}
