package no.julian.lightshade.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.config.SettingsDraft;
import no.julian.lightshade.pack.PackManager;
import no.julian.lightshade.pack.PackManager.Details;
import no.julian.lightshade.pack.PackManager.Option;

/** Shader Settings for the active pack: quality presets on top, then tabs (Lighting, Shadows, Water, Sky,
 * Nature, Atmosphere, Advanced) with two columns of options. Amounts are bars that fill with their value:
 * drag or click along the bar, − and + step, right-click or Shift steps down. Switches are ON/OFF pills.
 * Values away from the pack's default are gold. Edits live in the pack screen's draft until Apply/Done. */
public final class LightshadeSettingsScreen extends LightshadeScreen {
    static final int FOOTER = 34, ROW = 24;
    private final LightshadePackScreen parent;
    private final PackSelection selection;
    private final Details pack;
    private SettingsDraft draft;
    private Map<String,Double> snapshot;
    private String category;
    private double scroll;
    private int left, bodyWidth, optionsTop, optionsBottom, contentHeight;
    private final List<OptionWidget> optionWidgets = new ArrayList<>();
    private final List<LightshadePackScreen.Pill> presetPills = new ArrayList<>();
    private Button applyButton, doneButton, defaultsButton;

    LightshadeSettingsScreen(LightshadePackScreen parent, PackSelection selection, Details pack) {
        super(Component.literal(parent.displayName(pack.id())));
        this.parent = parent;
        this.selection = selection;
        this.pack = pack;
        snapshot = Map.copyOf(selection.draft(pack));
        draft = new SettingsDraft(pack.options(), snapshot);
        category = tabs().isEmpty() ? "Other" : tabs().getFirst();
    }
    List<String> tabs() {
        List<String> names = new ArrayList<>();
        for (String c : PackManager.CATEGORIES) if (pack.options().stream().anyMatch(o -> o.category().equals(c))) names.add(c);
        return names;
    }
    @Override protected int footerHeight() { return FOOTER; }
    @Override protected String subtitle() {
        int changed = draft.customized();
        return changed == 0 ? tr("lightshade.menu.settings", "Shader Settings")
            : tr("lightshade.menu.settings_changed", "Shader Settings · %s changed from the pack's defaults", changed);
    }

    @Override protected void init() {
        clearWidgets();
        optionWidgets.clear();
        presetPills.clear();
        bodyWidth = Math.min(width - 20, 520);
        left = (width - bodyWidth) / 2;
        int y = HEADER + 6;

        if (!pack.presets().isEmpty()) {
            int labelWidth = font.width(tr("lightshade.menu.quality", "Quality")) + 10, count = pack.presets().size();
            int each = (bodyWidth - labelWidth - 3 * (count - 1)) / count;
            for (int i = 0; i < count; i++) {
                var preset = pack.presets().get(i);
                int x = left + labelWidth + i * (each + 3), w = i == count - 1 ? bodyWidth - labelWidth - i * (each + 3) : each;
                var pill = addRenderableWidget(new LightshadePackScreen.Pill(x, y, w, 18, TEXTS.preset(preset.name()),
                    () -> selection.preset(pack).map(p -> p.name().equals(preset.name())).orElse(false), () -> {
                        selection.setDraft(pack.id(), draft.values());
                        selection.selectPreset(pack, preset);
                        draft = new SettingsDraft(pack.options(), selection.draft(pack));
                        note(tr("lightshade.menu.preset_chosen", "%s quality chosen. Apply or Done to use it.", TEXTS.preset(preset.name())), Tone.INFO, false);
                        refresh();
                    }));
                pill.setTooltip(Tooltip.create(Component.literal(tr("lightshade.menu.preset_tip",
                    "Sets shadows, reflections, clouds, light shafts and contact shading. Colours, sun and moon stay as you set them."))));
                presetPills.add(pill);
            }
            y += 24;
        }

        List<String> tabs = tabs();
        if (!tabs.isEmpty()) {
            int perRow = tabs.size(), widest = 0;
            for (String tab : tabs) widest = Math.max(widest, font.width(TEXTS.category(tab)) + 12);
            while (perRow > 1 && (bodyWidth - 3 * (perRow - 1)) / perRow < widest) perRow = (perRow + 1) / 2;
            int each = (bodyWidth - 3 * (perRow - 1)) / perRow;
            for (int i = 0; i < tabs.size(); i++) {
                String tab = tabs.get(i);
                int column = i % perRow, x = left + column * (each + 3), w = column == perRow - 1 ? bodyWidth - column * (each + 3) : each;
                addRenderableWidget(new LightshadePackScreen.Pill(x, y + (i / perRow) * 21, w, 18, TEXTS.category(tab), () -> tab.equals(category), () -> {
                    if (!tab.equals(category)) { category = tab; scroll = 0; init(); }
                }));
            }
            y += ((tabs.size() + perRow - 1) / perRow) * 21 + 6;
        }

        optionsTop = y;
        optionsBottom = height - FOOTER - 22;
        List<Option> visible = draft.options().stream().filter(o -> o.category().equals(category)).toList();
        int columns = bodyWidth >= 340 ? 2 : 1, columnWidth = (bodyWidth - 6 * (columns - 1)) / columns;
        contentHeight = ((visible.size() + columns - 1) / columns) * ROW;
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - (optionsBottom - optionsTop)));
        for (int i = 0; i < visible.size(); i++) {
            OptionWidget widget = new OptionWidget(visible.get(i), left + (i % columns) * (columnWidth + 6), 0, columnWidth, 20, i / columns);
            optionWidgets.add(addWidget(widget));
        }
        place();

        int footer = height - FOOTER + 8, each = (bodyWidth - 12) / 4;
        defaultsButton = addRenderableWidget(new MenuButton(left, footer, each, 20, Component.literal(tr("lightshade.menu.defaults", "Reset")), dir -> {
            draft.reset();
            changed();
            note(tr("lightshade.menu.defaults_done", "The pack's defaults are back. Apply or Done to use them."), Tone.INFO, false);
        }));
        addRenderableWidget(new MenuButton(left + (each + 4), footer, each, 20, Component.literal(tr("lightshade.menu.cancel", "Cancel")), dir -> {
            selection.setDraft(pack.id(), snapshot);
            minecraft.gui.setScreen(parent);
        }));
        applyButton = addRenderableWidget(new MenuButton(left + 2 * (each + 4), footer, each, 20, Component.literal(tr("lightshade.menu.apply", "Apply")), dir -> apply(false)));
        doneButton = addRenderableWidget(new MenuButton(left + 3 * (each + 4), footer, bodyWidth - 3 * (each + 4), 20, Component.literal(tr("lightshade.menu.done", "Done")),
            dir -> { if (selection.dirty(pack)) apply(true); else minecraft.gui.setScreen(parent); }));
        refresh();
    }
    /** Lays the option rows out under the scroll offset; rows outside the viewport take no clicks. */
    private void place() {
        for (OptionWidget widget : optionWidgets) {
            int y = optionsTop + widget.row * ROW - (int) scroll;
            widget.setY(y);
            widget.visible = y + widget.getHeight() > optionsTop && y < optionsBottom;
        }
    }
    private void changed() {
        selection.setDraft(pack.id(), draft.values());
        refresh();
    }
    private void refresh() {
        boolean busy = LightshadeClient.applying();
        for (var pill : presetPills) pill.active = !busy;
        for (OptionWidget widget : optionWidgets) widget.active = !busy && widget.option.values().size() > 1;
        if (applyButton != null) applyButton.active = !busy && LightshadeClient.allowed() && selection.dirty(pack);
        if (doneButton != null) doneButton.active = !busy;
        if (defaultsButton != null) defaultsButton.active = !busy && draft.customized() > 0;
    }
    private void apply(boolean close) {
        selection.setDraft(pack.id(), draft.values());
        String name = parent.displayName(pack.id());
        note(tr("lightshade.menu.compiling", "Compiling %s…", name), Tone.INFO, true);
        LightshadeClient.apply(pack.id(), true, draft.values()).whenComplete((unused, failure) -> minecraft.execute(() -> {
            if (failure != null) note(tr("lightshade.menu.failed", "Could not apply: %s", LightshadeClient.reason(failure)), Tone.ERROR, false);
            else {
                selection.committed(LightshadeClient.config().state());
                selection.activate(pack.id());
                snapshot = Map.copyOf(selection.draft(pack));
                draft = new SettingsDraft(pack.options(), snapshot);
                if (close) { minecraft.gui.setScreen(parent); return; }
                note(tr("lightshade.menu.saved", "Settings saved and in use."), Tone.OK, false);
            }
            refresh();
        }));
        refresh();
    }
    @Override public void tick() { refresh(); }
    @Override public boolean isPauseScreen() { return false; }
    /** Escape keeps the edits in the draft and goes back; the pack screen shows them as unsaved. */
    @Override public void onClose() { selection.setDraft(pack.id(), draft.values()); minecraft.gui.setScreen(parent); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        int max = Math.max(0, contentHeight - (optionsBottom - optionsTop));
        if (vertical != 0 && max > 0 && y >= optionsTop && y < optionsBottom) {
            scroll = Math.clamp(scroll - vertical * 16, 0, max);
            place();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        extractFrame(g);
        if (!presetPills.isEmpty()) g.text(font, tr("lightshade.menu.quality", "Quality"), left, HEADER + 11, MUTED, false);
        g.fill(left - 3, optionsTop - 3, left + bodyWidth + 3, optionsBottom + 3, PANEL);
        if (optionWidgets.isEmpty())
            g.centeredText(font, tr("lightshade.menu.empty", "This pack has no options here."), width / 2, optionsTop + 10, MUTED);
        g.enableScissor(left - 3, optionsTop - 2, left + bodyWidth + 3, optionsBottom + 2);
        for (OptionWidget widget : optionWidgets) if (widget.visible) widget.extractRenderState(g, mouseX, mouseY, delta);
        g.disableScissor();
        int max = Math.max(0, contentHeight - (optionsBottom - optionsTop));
        if (max > 0) {
            int track = optionsBottom - optionsTop, thumb = Math.max(16, track * track / (track + max));
            int at = optionsTop + (int) ((track - thumb) * (scroll / max));
            g.fill(left + bodyWidth + 1, at, left + bodyWidth + 3, at + thumb, 0x90FFFFFF);
        }
        extractBanner(g, left - 3, left + bodyWidth + 3);
        super.extractRenderState(g, mouseX, mouseY, delta);
        // Tooltips for rows: drawn after everything so the scissor does not cut them.
        for (OptionWidget widget : optionWidgets)
            if (widget.visible && widget.isMouseOver(mouseX, mouseY) && !widget.dragging)
                g.setTooltipForNextFrame(font, font.split(Component.literal(widget.help()), 240), mouseX, mouseY);
    }

    /** One option. Amounts: a bar filled to the value, with − and + at the ends. Switches: an ON/OFF pill. */
    final class OptionWidget extends AbstractWidget {
        static final int STEP = 14;
        final Option option;
        final int row;
        boolean dragging;
        OptionWidget(Option option, int x, int y, int w, int h, int row) {
            super(x, y, w, h, Component.literal(TEXTS.name(option)));
            this.option = option;
            this.row = row;
        }
        String help() {
            String text = TEXTS.help(option);
            String def = tr("lightshade.menu.default", "Default: %s", TEXTS.value(option, option.defaultValue()));
            String how = option.toggle() ? tr("lightshade.menu.how_toggle", "Click to switch.")
                : tr("lightshade.menu.how_slider", "Drag or click along the bar · − / + step · right-click or Shift steps down");
            return (text.isBlank() ? "" : text + "\n\n") + def + "\n" + how;
        }
        @Override public boolean isMouseOver(double x, double y) { return super.isMouseOver(x, y) && y >= optionsTop && y < optionsBottom; }
        @Override protected boolean isValidClickButton(MouseButtonInfo button) { return button.button() == 0 || button.button() == 1; }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
            boolean down = event.button() == 1 || event.hasShiftDown();
            if (option.toggle()) draft.cycle(option, 1);
            else if (down) draft.step(option, -1, true);
            else if (event.x() < getX() + STEP) draft.step(option, -1, false);
            else if (event.x() >= getX() + getWidth() - STEP) draft.step(option, 1, false);
            else { dragging = true; byPosition(event.x()); }
            changed();
        }
        @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            if (dragging && !option.toggle()) { byPosition(event.x()); changed(); }
        }
        @Override public void onRelease(MouseButtonEvent event) { dragging = false; }
        private void byPosition(double x) {
            double inner = getWidth() - 2.0 * STEP;
            draft.setFraction(option, (x - getX() - STEP) / Math.max(1, inner));
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (!active || !isFocused()) return false;
            int key = event.key();
            if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
                if (option.toggle()) draft.cycle(option, 1); else draft.step(option, key == InputConstants.KEY_LEFT ? -1 : 1, false);
                changed(); return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_SPACE || key == InputConstants.KEY_NUMPADENTER) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                draft.step(option, event.hasShiftDown() ? -1 : 1, true);
                changed(); return true;
            }
            return false;
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean hover = isHovered() && active, modified = !draft.isDefault(option);
            double value = draft.value(option.key());
            g.fill(x, y, x + w, y + h, hover ? 0xD0262B35 : 0xC0141720);
            g.outline(x, y, w, h, isFocused() ? 0xC0FFFFFF : hover ? 0x50FFFFFF : 0x24FFFFFF);
            String name = TEXTS.name(option), text = TEXTS.value(option, value);
            int valueColor = !active ? DIM : modified ? GOLD : TEXT;
            if (option.toggle()) {
                boolean on = value > 0;
                String label = on ? tr("lightshade.level.pill_on", "ON") : tr("lightshade.level.pill_off", "OFF");
                int pw = Math.max(26, font.width(label) + 10), px = x + w - pw - 4, py = y + 4;
                g.fill(px, py, px + pw, py + h - 8, on ? 0xFF2F8A55 : 0xFF3A3F49);
                g.fill(px + (on ? pw - 3 : 0), py, px + (on ? pw : 3), py + h - 8, on ? 0xFF7BE3A0 : 0xFF6B7280);
                g.centeredText(font, Component.literal(label).withStyle(ChatFormatting.BOLD), px + pw / 2, y + (h - 8) / 2, modified ? GOLD : TEXT);
                g.text(font, fit(name, w - pw - 14), x + 6, y + (h - 8) / 2, active ? TEXT : DIM, false);
                if (modified) g.fill(x + 2, y + 2, x + 4, y + h - 2, GOLD);
                return;
            }
            // The bar: filled to the value's place among the choices, with a tick per choice.
            int inner0 = x + STEP, inner1 = x + w - STEP, filled = inner0 + (int) Math.round((inner1 - inner0) * draft.fraction(option));
            g.fill(inner0, y + 2, filled, y + h - 2, modified ? 0x40FFC640 : 0x402D86C0);
            g.fill(inner0, y + h - 4, filled, y + h - 2, modified ? 0xFFE0A92E : 0xFF4DA8E0);
            List<Double> choices = option.sorted();
            for (int i = 0; i < choices.size(); i++) {
                int tx = inner0 + (int) Math.round((inner1 - inner0 - 1) * (choices.size() < 2 ? 1.0 : (double) i / (choices.size() - 1)));
                g.fill(tx, y + h - 6, tx + 1, y + h - 4, tx <= filled ? 0xA0FFFFFF : 0x50FFFFFF);
            }
            boolean onMinus = hover && mx < x + STEP, onPlus = hover && mx >= x + w - STEP;
            g.centeredText(font, "−", x + STEP / 2, y + (h - 8) / 2, onMinus ? TEXT : MUTED);
            g.centeredText(font, "+", x + w - STEP / 2, y + (h - 8) / 2, onPlus ? TEXT : MUTED);
            int valueWidth = font.width(text);
            g.text(font, fit(name, inner1 - inner0 - valueWidth - 10), inner0 + 3, y + (h - 8) / 2, active ? TEXT : DIM, true);
            g.text(font, text, inner1 - 3 - valueWidth, y + (h - 8) / 2, valueColor, true);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.literal(TEXTS.name(option) + ": " + TEXTS.value(option, draft.value(option.key()))));
        }
    }
}
