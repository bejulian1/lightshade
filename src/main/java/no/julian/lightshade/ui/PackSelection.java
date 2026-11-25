package no.julian.lightshade.ui;

import java.util.*;
import no.julian.lightshade.config.LightshadeConfig;
import no.julian.lightshade.config.QualityPresets;
import no.julian.lightshade.pack.PackManager.Details;
import no.julian.lightshade.pack.PackManager.Entry;
import no.julian.lightshade.pack.PackManager.Preset;

/** What the shader pack screen shows and edits, without any Minecraft GUI code, so it can be tested
 * offline. Like the resource pack screen: packs sit in "available" until one is moved to "active";
 * at most one is active. Option drafts are kept per pack until Apply, Done or Cancel. */
public final class PackSelection {
    private LightshadeConfig.State saved;
    private List<Entry> packs = List.of();
    private String active;
    private final Map<String,Map<String,Double>> drafts = new HashMap<>();

    public PackSelection(LightshadeConfig.State saved) {
        this.saved = saved;
        active = saved.enabled() ? saved.selectedPack() : null;
    }
    public void setPacks(List<Entry> list) {
        List<Entry> result = new ArrayList<>(list);
        // A saved or active pack that has vanished from the folder still shows, with a problem.
        for (String id : new String[]{active, saved.selectedPack()})
            if (id != null && result.stream().noneMatch(e -> e.id().equals(id)))
                result.add(new Entry(id, id, "", "", false, "Not found in shaderpacks"));
        packs = List.copyOf(result);
    }
    public List<Entry> packs() { return packs; }
    /** Left column: every pack except the active one. */
    public List<Entry> available() { return packs.stream().filter(e -> !e.id().equals(active)).toList(); }
    public Optional<Entry> activeEntry() { return active == null ? Optional.empty() : packs.stream().filter(e -> e.id().equals(active)).findFirst(); }
    public String active() { return active; }
    public boolean enabled() { return active != null; }
    /** Moves a pack to the active column; the previous active pack goes back to available. */
    public boolean activate(String id) {
        Entry entry = packs.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        if (entry == null || !entry.usable()) return false;
        active = id;
        return true;
    }
    public void deactivate() { active = null; }
    /** The pack whose settings Apply would use: the active one, or the saved one when shaders go off. */
    public String target() { return active != null ? active : saved.selectedPack(); }

    public Map<String,Double> draft(Details details) {
        return drafts.computeIfAbsent(details.id(), id -> new TreeMap<>(details.accepted(saved.options(id))));
    }
    public void setDraft(String id, Map<String,Double> values) { drafts.put(id, new TreeMap<>(values)); }
    public void selectPreset(Details details, Preset preset) {
        Map<String,Double> values = new TreeMap<>(draft(details));
        values.putAll(preset.values());
        drafts.put(details.id(), values);
    }
    public Optional<Preset> preset(Details details) { return QualityPresets.matching(details.presets(), draft(details)); }
    /** Options of the pack set away from the pack's own defaults, in the draft. */
    public int customized(Details details) { return details.customized(draft(details)); }
    /** Options changed in the draft compared with what is saved. */
    public boolean optionsChanged(Details details) { return !draft(details).equals(details.accepted(saved.options(details.id()))); }
    /** Anything for Apply to do. details: the active pack's, or null when it cannot be read. */
    public boolean dirty(Details details) {
        boolean wasOn = saved.enabled();
        if (enabled() != wasOn) return true;
        if (!enabled()) return false;
        if (!active.equals(saved.selectedPack())) return true;
        return details != null && optionsChanged(details);
    }
    /** After a successful apply: what is saved now; drafts start again from it. */
    public void committed(LightshadeConfig.State now) { saved = now; drafts.clear(); }
    public LightshadeConfig.State saved() { return saved; }
}
