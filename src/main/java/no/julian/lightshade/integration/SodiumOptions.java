package no.julian.lightshade.integration;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import no.julian.lightshade.LightshadeClient;

/** Sodium replaces Video Settings with its own screen; this puts "Shader Packs" in its sidebar
 * (entrypoint sodium:config_api_user), beside the button on the first Options screen. */
public final class SodiumOptions implements ConfigEntryPoint {
    @Override public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .setName("Lightshade Shaders")
            .setIcon(Identifier.fromNamespaceAndPath("lightshade", "textures/gui/config-icon.png"))
            .addPage(builder.createExternalPage()
                .setName(Component.translatable("lightshade.menu.packs"))
                .setScreenConsumer(LightshadeClient::openMenu));
    }
}
