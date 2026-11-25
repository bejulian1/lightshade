package no.julian.lightshade.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import no.julian.lightshade.LightshadeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** A full-width "Shader Packs..." row under the ten buttons of the first Options screen. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    private static final int ROW_AFTER_THE_BUTTONS=5;
    @ModifyArg(method="init",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
    private LayoutElement lightshade$shaderPacks(LayoutElement contents){
        if(contents instanceof GridLayout grid){
            Screen self=(Screen)(Object)this;
            grid.addChild(Button.builder(Component.translatable("lightshade.menu.open"),button->LightshadeClient.openMenu(self)).width(310).build(),ROW_AFTER_THE_BUTTONS,0,1,2);
        }
        return contents;
    }
}
