package no.julian.lightshade.ui;

import java.util.*;
import java.util.IllegalFormatException;
import java.util.function.IntConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** The frame both Lightshade screens share: Minecraft's blurred menu background, header and footer
 * separators, a centred title with a status line, and a status banner above the footer. */
abstract class LightshadeScreen extends Screen {
    static final int HEADER = 34;
    static final int TEXT = 0xFFFFFFFF, MUTED = 0xFFA9B2BF, DIM = 0xFF6F7886, GOLD = 0xFFFFCB47, ACCENT = 0xFF5CB8EC,
        OK = 0xFF72DD8F, ERROR = 0xFFFF6E6E, PANEL = 0x9A0C0E13, CARD = 0xB0101319, CARD_HOVER = 0xC0222731, EDGE = 0x33FFFFFF;
    enum Tone { INFO, OK, ERROR }
    private String banner = "";
    private Tone tone = Tone.INFO;
    private long bannerUntil;

    protected LightshadeScreen(Component title) { super(title); }
    /** Height of the bottom band, including its separator. */
    protected abstract int footerHeight();
    /** The grey line under the title when there is no banner. */
    protected abstract String subtitle();

    /** Shows a banner for a few seconds, or until replaced (sticky: until replaced). */
    protected void note(String text, Tone kind, boolean sticky) {
        banner = text == null ? "" : text;
        tone = kind;
        bannerUntil = sticky ? Long.MAX_VALUE : System.currentTimeMillis() + 7000;
    }
    protected void clearNote() { banner = ""; }
    protected boolean noteIsError() { return !banner.isEmpty() && tone == Tone.ERROR && System.currentTimeMillis() < bannerUntil; }

    protected void extractFrame(GuiGraphicsExtractor g) {
        boolean inWorld = minecraft.level != null;
        int footerTop = height - footerHeight();
        g.blit(RenderPipelines.GUI_TEXTURED, inWorld ? INWORLD_HEADER_SEPARATOR : HEADER_SEPARATOR, 0, HEADER - 2, 0, 0, width, 2, 32, 2);
        g.blit(RenderPipelines.GUI_TEXTURED, inWorld ? INWORLD_FOOTER_SEPARATOR : FOOTER_SEPARATOR, 0, footerTop, 0, 0, width, 2, 32, 2);
        g.centeredText(font, getTitle(), width / 2, 8, TEXT);
        g.centeredText(font, Component.literal(fit(subtitle(), width - 16)).withStyle(ChatFormatting.ITALIC), width / 2, 20, MUTED);
    }
    /** The banner sits just above the footer, over whatever is there. */
    protected void extractBanner(GuiGraphicsExtractor g, int left, int right) {
        if (banner.isEmpty() || System.currentTimeMillis() > bannerUntil) return;
        int bottom = height - footerHeight() - 4, top = bottom - 16;
        int color = tone == Tone.ERROR ? 0xE0401A1E : tone == Tone.OK ? 0xE0133524 : 0xE0122633;
        int edge = tone == Tone.ERROR ? ERROR : tone == Tone.OK ? OK : ACCENT;
        g.fill(left, top, right, bottom, color);
        g.fill(left, top, left + 2, bottom, edge);
        g.text(font, fit(banner, right - left - 12), left + 8, top + 4, tone == Tone.ERROR ? 0xFFFFD6D6 : TEXT, false);
    }

    protected String fit(String text, int max) {
        return font.width(text) <= max ? text : font.plainSubstrByWidth(text, Math.max(1, max - font.width("…"))) + "…";
    }
    /** Word wrap into at most maxLines lines; the last one ends in … when text is left over. */
    protected List<String> wrap(String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        String[] words = text.strip().split("\\s+");
        StringBuilder line = new StringBuilder();
        boolean overflow = false;
        for (String word : words) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(next) <= width) { line.setLength(0); line.append(next); continue; }
            if (!line.isEmpty()) { lines.add(line.toString()); line.setLength(0); }
            if (lines.size() == maxLines) { overflow = true; break; }
            if (font.width(word) > width) {
                lines.add(fit(word, width));
                if (lines.size() == maxLines) { overflow = true; break; }
            } else line.append(word);
        }
        if (!overflow && !line.isEmpty()) {
            if (lines.size() < maxLines) lines.add(line.toString()); else overflow = true;
        }
        if (overflow && !lines.isEmpty()) {
            int last = lines.size() - 1;
            String end = lines.get(last);
            while (!end.isEmpty() && font.width(end + "…") > width) end = end.substring(0, end.length() - 1);
            lines.set(last, end.stripTrailing() + "…");
        }
        return lines;
    }
    static String tr(String key, String fallback, Object... args) {
        String pattern = Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : fallback;
        try { return args.length == 0 ? pattern : String.format(pattern, args); } catch (IllegalFormatException bad) { return pattern; }
    }
    static final OptionText TEXTS = new OptionText((key, fallback) -> tr(key, fallback));

    /** A vanilla button that also knows right-click and Shift: direction -1 then. */
    static final class MenuButton extends Button {
        private final IntConsumer action;
        MenuButton(int x, int y, int w, int h, Component text, IntConsumer action) {
            super(x, y, w, h, text, b -> {}, DEFAULT_NARRATION);
            this.action = action;
        }
        @Override public void onPress(InputWithModifiers input) { action.accept(input.hasShiftDown() ? -1 : 1); }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            extractDefaultSprite(g);
            extractDefaultLabel(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }
}
