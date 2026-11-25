package no.julian.lightshade.ui;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import no.julian.lightshade.LightshadeClient;

/** Pack icons (pack.png, any square size up to 256 KiB) as GUI textures. Built-in and ZIP icons take the
 * same path: read once, uploaded as a DynamicTexture, released when the list is read again. A pack
 * without an icon, or with one that cannot be read, gets the default icon. */
final class PackIcons {
    record Icon(Identifier id, int size) {}
    static final Icon DEFAULT = new Icon(Identifier.fromNamespaceAndPath(LightshadeClient.MOD_ID, "textures/gui/default_pack.png"), 64);
    private static final Map<String,Icon> LOADED = new HashMap<>();
    private static int serial;
    private PackIcons() {}

    static Icon get(String packId) {
        Icon cached = LOADED.get(packId);
        if (cached != null) return cached;
        Icon icon = DEFAULT;
        byte[] bytes = LightshadeClient.packs().icon(packId);
        if (bytes != null) {
            NativeImage image = null;
            try {
                image = NativeImage.read(bytes);
                int w = image.getWidth(), h = image.getHeight();
                if (w == h && w >= 16 && w <= 512) {
                    Identifier id = Identifier.fromNamespaceAndPath(LightshadeClient.MOD_ID, "pack_icon/" + (serial++));
                    NativeImage owned = image;
                    image = null;
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Lightshade pack icon " + packId, owned));
                    icon = new Icon(id, w);
                }
            } catch (Exception unreadable) {
                icon = DEFAULT;
            } finally {
                if (image != null) image.close();
            }
        }
        LOADED.put(packId, icon);
        return icon;
    }
    /** Forget every uploaded icon; the next get() reads the pack again. */
    static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (Icon icon : LOADED.values()) if (icon != DEFAULT) textures.release(icon.id());
        LOADED.clear();
    }
}
