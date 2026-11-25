package no.julian.lightshade.ui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.pack.PackManager;
import no.julian.lightshade.pack.PackManager.Details;
import no.julian.lightshade.pack.PackManager.Entry;

/** Shader packs, laid out like Minecraft's resource pack screen: available packs on the left, the
 * active pack on the right. ▶ on a pack's icon (or a double click) makes it the active shader, ◀ turns
 * shaders off. Quality presets and the settings button sit right under the active pack. Nothing changes
 * in the game until Apply or Done has compiled the choice; Cancel and Escape discard it. */
public final class LightshadePackScreen extends LightshadeScreen {
    static final int CARD_H = 42, CARD_GAP = 3, ICON = 32, FOOTER = 58;
    private static final Identifier SELECT = Identifier.withDefaultNamespace("transferable_list/select");
    private static final Identifier SELECT_HOVER = Identifier.withDefaultNamespace("transferable_list/select_highlighted");
    private static final Identifier UNSELECT = Identifier.withDefaultNamespace("transferable_list/unselect");
    private static final Identifier UNSELECT_HOVER = Identifier.withDefaultNamespace("transferable_list/unselect_highlighted");
    private static final int[] BADGES = {0xFF2C7FB8, 0xFF7B52C9, 0xFF2E9A6A, 0xFFB8743A};

    private final Screen parent;
    private final PackSelection selection;
    private final Map<String,Details> details = new HashMap<>();
    private String focused;
    private double scroll;
    private int columnWidth, leftX, rightX, listTop, listBottom;
    private Button applyButton, doneButton, settingsButton;
    private final List<Pill> presetPills = new ArrayList<>();

    public LightshadePackScreen(Screen parent) {
        super(Component.translatable("lightshade.menu.packs"));
        this.parent = parent;
        selection = new PackSelection(LightshadeClient.config().state());
        readPacks();
        if (!LightshadeClient.allowed()) note(LightshadeClient.guardReason(), Tone.ERROR, true);
        else if (LightshadeClient.problemText() != null) note(LightshadeClient.problemText(), Tone.ERROR, false);
    }
    private void readPacks() {
        details.clear();
        try { selection.setPacks(LightshadeClient.packs().available()); }
        catch (Exception failure) { selection.setPacks(List.of()); note(LightshadeClient.reason(failure), Tone.ERROR, false); }
    }
    Details details(String id) {
        if (id == null) return null;
        if (details.containsKey(id)) return details.get(id);
        Details read;
        try { read = LightshadeClient.packs().describe(id); } catch (Exception unreadable) { read = null; }
        details.put(id, read);
        return read;
    }
    PackSelection selection() { return selection; }

    @Override protected int footerHeight() { return FOOTER; }
    @Override protected String subtitle() { return tr("lightshade.menu.subtitle", "Lightshade Shader Loader · O"); }

    @Override protected void init() {
        clearWidgets();
        presetPills.clear();
        int gap = 10;
        columnWidth = Math.min(250, (width - 3 * gap) / 2);
        leftX = (width - 2 * columnWidth - gap) / 2;
        rightX = leftX + columnWidth + gap;
        listTop = HEADER + 16;
        listBottom = height - FOOTER - 22;
        scroll = Math.clamp(scroll, 0, maxScroll());

        // The right pane under the active card: quality presets and the settings button.
        Details active = details(selection.active());
        int y = listTop + CARD_H + 18;
        if (active != null && !active.presets().isEmpty()) {
            int count = active.presets().size(), each = (columnWidth - 3 * (count - 1)) / count;
            for (int i = 0; i < count; i++) {
                var preset = active.presets().get(i);
                Pill pill = addRenderableWidget(new Pill(rightX + i * (each + 3), y, i == count - 1 ? columnWidth - i * (each + 3) : each, 18,
                    TEXTS.preset(preset.name()), () -> selection.preset(active).map(p -> p.name().equals(preset.name())).orElse(false), () -> {
                        selection.selectPreset(active, preset);
                        note(tr("lightshade.menu.preset_chosen", "%s quality chosen. Apply or Done to use it.", TEXTS.preset(preset.name())), Tone.INFO, false);
                        refresh();
                    }));
                pill.setTooltip(Tooltip.create(Component.literal(tr("lightshade.menu.preset_tip",
                    "Sets shadows, reflections, clouds, light shafts and contact shading. Colours, sun and moon stay as you set them."))));
                presetPills.add(pill);
            }
            y += 24;
        }
        settingsButton = addRenderableWidget(new MenuButton(rightX, y, columnWidth, 20, Component.empty(), dir -> openSettings()));
        settingsButton.visible = active != null;

        int row1 = height - FOOTER + 8, row2 = row1 + 24, third = (2 * columnWidth + gap - 8) / 3;
        addRenderableWidget(new MenuButton(leftX, row1, columnWidth, 20, Component.literal(tr("lightshade.menu.open_folder", "Open Pack Folder...")), dir -> openFolder()));
        addRenderableWidget(new MenuButton(rightX, row1, columnWidth, 20, Component.literal(tr("lightshade.menu.refresh", "Reload")), dir -> {
            PackIcons.clear(); readPacks();
            note(tr("lightshade.menu.refreshed", "The pack list has been read again."), Tone.INFO, false);
            rebuild();
        })).setTooltip(Tooltip.create(Component.literal(tr("lightshade.menu.refresh_tip", "Read shaderpacks/ again. Packs are ZIPs or folders with lightshade.json at the root."))));
        addRenderableWidget(new MenuButton(leftX, row2, third, 20, Component.literal(tr("lightshade.menu.cancel", "Cancel")), dir -> onClose()));
        applyButton = addRenderableWidget(new MenuButton(leftX + third + 4, row2, third, 20, Component.literal(tr("lightshade.menu.apply", "Apply")), dir -> apply(false)));
        doneButton = addRenderableWidget(new MenuButton(leftX + 2 * (third + 4), row2, 2 * columnWidth + gap - 2 * (third + 4), 20,
            Component.literal(tr("lightshade.menu.done", "Done")), dir -> { if (dirty()) apply(true); else onClose(); }));
        refresh();
    }
    private void rebuild() { init(); }
    private boolean dirty() { return selection.dirty(details(selection.active())); }
    private void refresh() {
        boolean busy = LightshadeClient.applying();
        Details active = details(selection.active());
        if (settingsButton != null) {
            int changed = active == null ? 0 : selection.customized(active);
            String label = tr("lightshade.menu.pack_settings", "Shader Settings...");
            if (changed > 0) label += " " + tr("lightshade.menu.changed_count", "(%s changed)", changed);
            settingsButton.setMessage(Component.literal(label));
            settingsButton.active = !busy && active != null && !active.options().isEmpty();
        }
        for (Pill pill : presetPills) pill.active = !busy;
        if (applyButton != null) applyButton.active = !busy && LightshadeClient.allowed() && dirty();
        if (doneButton != null) doneButton.active = !busy;
    }
    private void openSettings() {
        Details active = details(selection.active());
        if (active != null) minecraft.gui.setScreen(new LightshadeSettingsScreen(this, selection, active));
    }
    /** Called by the settings screen: compile and save what the selection holds now. */
    void apply(boolean close) {
        String id = selection.target();
        boolean on = selection.enabled();
        Details target = details(id);
        Map<String,Double> options = target != null ? selection.draft(target) : selection.saved().options(id);
        String name = displayName(id);
        note(on ? tr("lightshade.menu.compiling", "Compiling %s…", name) : tr("lightshade.menu.turning_off", "Turning shaders off…"), Tone.INFO, true);
        LightshadeClient.apply(id, on, options).whenComplete((unused, failure) -> minecraft.execute(() -> {
            if (failure != null) note(tr("lightshade.menu.failed", "Could not apply: %s", LightshadeClient.reason(failure)), Tone.ERROR, false);
            else {
                selection.committed(LightshadeClient.config().state());
                if (close) { minecraft.gui.setScreen(parent); return; }
                note(on ? tr("lightshade.menu.applied", "%s is on.", name) : tr("lightshade.menu.applied_off", "Shaders are off."), Tone.OK, false);
            }
            if (minecraft.gui.screen() == this) rebuild();
        }));
        refresh();
    }
    String displayName(String id) {
        Entry entry = selection.packs().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        return tr("lightshade.pack." + id, entry == null ? id : entry.name());
    }
    private String description(Entry entry) {
        if (!entry.usable()) return entry.problem();
        return tr("lightshade.pack." + entry.id() + ".desc", entry.description());
    }
    private String tag(Entry entry) {
        if (!entry.usable()) return tr("lightshade.menu.broken", "Error");
        String tag = tr("lightshade.pack." + entry.id() + ".tag", entry.tag());
        return tag == null ? "" : tag;
    }
    private void openFolder() {
        try { Files.createDirectories(LightshadeClient.packs().directory()); Blaze3D.openPath(LightshadeClient.packs().directory()); }
        catch (IOException failure) { note(LightshadeClient.reason(failure), Tone.ERROR, false); }
    }

    /** Dropped ZIPs and pack folders are copied into shaderpacks; the last good one becomes active. */
    @Override public void onFilesDrop(List<Path> dropped) {
        Path directory = LightshadeClient.packs().directory();
        String added = null, problem = null;
        int count = 0;
        for (Path path : dropped) {
            String name = path.getFileName().toString();
            try {
                Files.createDirectories(directory);
                if (!name.matches("[A-Za-z0-9_. -]+")) throw new IOException(tr("lightshade.menu.bad_name", "%s: use only letters, digits, spaces, dots, dashes and underscores in the name.", name));
                if (Files.isDirectory(path)) {
                    if (!Files.isRegularFile(path.resolve(PackManager.MANIFEST))) throw new IOException(tr("lightshade.menu.not_a_pack", "%s is not a Lightshade pack (no lightshade.json).", name));
                    try (var walk = Files.walk(path)) {
                        for (Path file : walk.toList()) {
                            if (Files.isSymbolicLink(file)) continue;
                            Path target = directory.resolve(name).resolve(path.relativize(file).toString()).normalize();
                            if (!target.startsWith(directory)) continue;
                            if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) Files.copy(path, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                else throw new IOException(tr("lightshade.menu.not_a_zip", "%s is not a ZIP file or a pack folder.", name));
                LightshadeClient.packs().describe(name);
                added = name;
                count++;
            } catch (Exception failure) { problem = LightshadeClient.reason(failure); }
        }
        PackIcons.clear();
        readPacks();
        if (added != null) {
            selection.activate(added);
            focused = added;
            String text = count == 1 ? tr("lightshade.menu.added", "%s added and chosen. Apply or Done to use it.", displayName(added))
                : tr("lightshade.menu.added_many", "%s packs added.", count);
            note(problem == null ? text : text + " " + problem, problem == null ? Tone.OK : Tone.ERROR, false);
        } else if (problem != null) note(problem, Tone.ERROR, false);
        rebuild();
    }
    @Override public void tick() { refresh(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void removed() { PackIcons.clear(); }

    // ---- the left list
    private int maxScroll() {
        int content = selection.available().size() * (CARD_H + CARD_GAP);
        return Math.max(0, content - (listBottom - listTop));
    }
    private int cardY(int index) { return listTop + index * (CARD_H + CARD_GAP) - (int) scroll; }
    private int hoveredAvailable(double mx, double my) {
        if (mx < leftX || mx >= leftX + columnWidth || my < listTop || my >= listBottom) return -1;
        List<Entry> list = selection.available();
        for (int i = 0; i < list.size(); i++) { int y = cardY(i); if (my >= y && my < y + CARD_H) return i; }
        return -1;
    }
    private boolean overActive(double mx, double my) {
        return selection.activeEntry().isPresent() && mx >= rightX && mx < rightX + columnWidth && my >= listTop && my < listTop + CARD_H;
    }
    private static boolean overIcon(double mx, double my, int x, int y) { return mx >= x + 5 && mx < x + 5 + ICON && my >= y + 5 && my < y + 5 + ICON; }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0 || LightshadeClient.applying()) return false;
        int index = hoveredAvailable(event.x(), event.y());
        if (index >= 0) {
            Entry entry = selection.available().get(index);
            focused = entry.id();
            if (overIcon(event.x(), event.y(), leftX, cardY(index)) || doubleClick) activate(entry);
            else AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
            return true;
        }
        if (overActive(event.x(), event.y())) {
            if (overIcon(event.x(), event.y(), rightX, listTop) || doubleClick) {
                String was = selection.active();
                selection.deactivate();
                focused = was;
                AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
                note(tr("lightshade.menu.off_note", "No shader chosen. Apply or Done turns shaders off."), Tone.INFO, false);
                rebuild();
            }
            return true;
        }
        return false;
    }
    private void activate(Entry entry) {
        AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
        if (!selection.activate(entry.id())) { note(description(entry), Tone.ERROR, false); return; }
        note(tr("lightshade.menu.chosen", "%s chosen. Apply or Done to use it.", displayName(entry.id())), Tone.INFO, false);
        rebuild();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && x >= leftX && x < leftX + columnWidth && y >= listTop && y < listBottom) {
            scroll = Math.clamp(scroll - vertical * 18, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        List<Entry> list = selection.available();
        int key = event.key();
        if ((key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) && !list.isEmpty()) {
            int index = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(focused)) index = i;
            index = Math.clamp(index + (key == InputConstants.KEY_UP ? -1 : 1), 0, list.size() - 1);
            focused = list.get(index).id();
            int y = index * (CARD_H + CARD_GAP);
            if (y < scroll) scroll = y;
            if (y + CARD_H > scroll + (listBottom - listTop)) scroll = y + CARD_H - (listBottom - listTop);
            scroll = Math.clamp(scroll, 0, maxScroll());
            return true;
        }
        if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && focused != null && getFocused() == null) {
            list.stream().filter(e -> e.id().equals(focused)).findFirst().ifPresent(this::activate);
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- drawing
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        extractFrame(g);
        g.text(font, Component.literal(tr("lightshade.menu.available", "Available Packs")).withStyle(ChatFormatting.BOLD), leftX + 2, HEADER + 4, TEXT, true);
        g.text(font, Component.literal(tr("lightshade.menu.active", "Active Pack")).withStyle(ChatFormatting.BOLD), rightX + 2, HEADER + 4, TEXT, true);

        // Left column.
        List<Entry> list = selection.available();
        g.fill(leftX - 2, listTop - 2, leftX + columnWidth + 2, listBottom + 2, PANEL);
        g.enableScissor(leftX - 2, listTop - 1, leftX + columnWidth + 2, listBottom + 1);
        int hovered = hoveredAvailable(mouseX, mouseY);
        for (int i = 0; i < list.size(); i++) {
            int y = cardY(i);
            if (y + CARD_H < listTop || y > listBottom) continue;
            card(g, list.get(i), leftX, y, columnWidth, i == hovered, mouseX, mouseY, true);
        }
        int hintY = cardY(list.size()) + 4;
        if (hintY + 9 < listBottom)
            g.centeredText(font, Component.literal(fit(tr("lightshade.menu.drop_hint", "+ Drop pack ZIPs here to add them"), columnWidth - 8)).withStyle(ChatFormatting.ITALIC), leftX + columnWidth / 2, hintY, DIM);
        g.disableScissor();
        if (maxScroll() > 0) {
            int track = listBottom - listTop, thumb = Math.max(16, track * track / (track + maxScroll()));
            int at = listTop + (int) ((track - thumb) * (scroll / maxScroll()));
            g.fill(leftX + columnWidth - 3, at, leftX + columnWidth - 1, at + thumb, 0x90FFFFFF);
        }

        // Right column.
        g.fill(rightX - 2, listTop - 2, rightX + columnWidth + 2, listBottom + 2, PANEL);
        var active = selection.activeEntry();
        if (active.isPresent()) {
            card(g, active.get(), rightX, listTop, columnWidth, overActive(mouseX, mouseY), mouseX, mouseY, false);
            Details details = details(active.get().id());
            if (details != null && !details.presets().isEmpty())
                g.text(font, tr("lightshade.menu.quality", "Quality"), rightX + 2, listTop + CARD_H + 7, MUTED, false);
            int infoY = settingsButton.getY() + 26;
            String info = dirty() ? tr("lightshade.menu.pending", "Unsaved changes: Apply or Done compiles them.")
                : tr("lightshade.menu.running", "This shader is running.");
            int color = dirty() ? GOLD : DIM;
            for (String line : wrap(info, columnWidth - 4, 3)) {
                if (infoY + 9 > listBottom) break;
                g.text(font, line, rightX + 2, infoY, color, false);
                infoY += 10;
            }
        } else {
            int y = listTop;
            g.outline(rightX, y, columnWidth, CARD_H, 0x40FFFFFF);
            g.centeredText(font, Component.literal(fit(tr("lightshade.menu.none", "No shader active"), columnWidth - 8)).withStyle(ChatFormatting.BOLD), rightX + columnWidth / 2, y + 9, MUTED);
            List<String> lines = wrap(tr("lightshade.menu.none_hint", "Click ▶ on a pack to the left. Until then Minecraft draws the world itself."), columnWidth - 12, 2);
            for (int i = 0; i < lines.size(); i++) g.centeredText(font, lines.get(i), rightX + columnWidth / 2, y + 21 + i * 10, DIM);
            if (dirty()) {
                List<String> pending = wrap(tr("lightshade.menu.pending", "Unsaved changes: Apply or Done compiles them."), columnWidth - 4, 3);
                for (int i = 0; i < pending.size(); i++) g.text(font, pending.get(i), rightX + 2, y + CARD_H + 10 + i * 10, GOLD, false);
            }
        }
        extractBanner(g, leftX - 2, rightX + columnWidth + 2);
        super.extractRenderState(g, mouseX, mouseY, delta);

        // A tooltip with the whole description for the card under the mouse, unless the icon is hovered.
        Entry tip = hovered >= 0 ? list.get(hovered) : overActive(mouseX, mouseY) ? active.get() : null;
        if (tip != null) {
            String text = description(tip);
            if (text != null && !text.isBlank()) g.setTooltipForNextFrame(font, font.split(Component.literal(text), 220), mouseX, mouseY);
        }
    }
    private void card(GuiGraphicsExtractor g, Entry entry, int x, int y, int w, boolean hovered, int mx, int my, boolean available) {
        boolean focus = entry.id().equals(focused) && available;
        g.fill(x, y, x + w, y + CARD_H, hovered ? CARD_HOVER : CARD);
        if (!entry.usable()) g.fill(x, y, x + 2, y + CARD_H, ERROR);
        else if (!available) g.fill(x, y, x + 2, y + CARD_H, ACCENT);
        if (focus) g.outline(x, y, w, CARD_H, 0xA0FFFFFF);
        else g.outline(x, y, w, CARD_H, EDGE);

        PackIcons.Icon icon = PackIcons.get(entry.id());
        int ix = x + 5, iy = y + 5;
        g.blit(RenderPipelines.GUI_TEXTURED, icon.id(), ix, iy, 0, 0, ICON, ICON, icon.size(), icon.size(), icon.size(), icon.size());
        if (hovered && entry.usable()) {
            boolean onIcon = overIcon(mx, my, x, y);
            g.fill(ix, iy, ix + ICON, iy + ICON, 0xA0909090);
            Identifier sprite = available ? (onIcon ? SELECT_HOVER : SELECT) : (onIcon ? UNSELECT_HOVER : UNSELECT);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, ix, iy, ICON, ICON);
        }

        int textX = x + 42, textW = w - 46;
        String tag = tag(entry);
        int badgeW = tag.isEmpty() ? 0 : Math.min(font.width(tag) + 8, textW / 2);
        String title = fit(displayName(entry.id()), textW - (badgeW > 0 ? badgeW + 4 : 0));
        g.text(font, Component.literal(title).withStyle(ChatFormatting.BOLD), textX, y + 5, entry.usable() ? TEXT : 0xFFFFB3B3, true);
        if (badgeW > 0) {
            int bx = x + w - 4 - badgeW, color = entry.usable() ? BADGES[Math.floorMod(tag.hashCode(), BADGES.length)] : 0xFF9A2F35;
            g.fill(bx, y + 4, bx + badgeW, y + 15, (color & 0x00FFFFFF) | 0x70000000);
            g.outline(bx, y + 4, badgeW, 11, color);
            g.centeredText(font, fit(tag, badgeW - 4), bx + badgeW / 2, y + 6, 0xFFEFF6FF);
        }
        List<String> lines = wrap(description(entry), textW, 2);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), textX, y + 18 + i * 10, entry.usable() ? MUTED : 0xFFE0A0A0, false);
    }

    /** A rounded-looking toggle chip; selected chips are filled with the accent colour. */
    static final class Pill extends AbstractWidget {
        private final java.util.function.BooleanSupplier selected;
        private final Runnable action;
        Pill(int x, int y, int w, int h, String label, java.util.function.BooleanSupplier selected, Runnable action) {
            super(x, y, w, h, Component.literal(label));
            this.selected = selected;
            this.action = action;
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { action.run(); }
        @Override public boolean keyPressed(KeyEvent event) {
            if (active && isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_NUMPADENTER)) {
                playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
                action.run();
                return true;
            }
            return false;
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            boolean on = selected.getAsBoolean(), hover = isHoveredOrFocused() && active;
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int fill = on ? 0xFF2D6E96 : hover ? 0xD0303642 : 0xC0181B22;
            // Corners left out: reads as a rounded chip at Minecraft's pixel scale.
            g.fill(x + 1, y, x + w - 1, y + h, fill);
            g.fill(x, y + 1, x + 1, y + h - 1, fill);
            g.fill(x + w - 1, y + 1, x + w, y + h - 1, fill);
            if (on) { g.fill(x + 1, y, x + w - 1, y + 1, 0xFF8FD3FF); }
            else if (hover) g.fill(x + 1, y + h - 1, x + w - 1, y + h, 0x60FFFFFF);
            int color = !active ? DIM : on ? TEXT : MUTED;
            Component label = on ? getMessage().copy().withStyle(ChatFormatting.BOLD) : getMessage();
            String text = label.getString();
            if (font.width(label) > w - 4) label = Component.literal(font.plainSubstrByWidth(text, Math.max(1, w - 6)));
            g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, color);
        }
        @Override protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
