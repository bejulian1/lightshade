package no.julian.lightshade.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import no.julian.lightshade.render.LightshadeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recompile resource includes only after Minecraft has replaced its resources. */
@Mixin(Minecraft.class)
public abstract class MinecraftReloadMixin {
    @Inject(method="reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
            at=@At("RETURN"), cancellable=true)
    private void lightshade$reload(boolean recovery, GameLoadCookie cookie,
            CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        callback.setReturnValue(callback.getReturnValue().thenCompose(unused ->
            LightshadeRenderer.resourceReloaded().exceptionally(failure -> {
                System.getLogger("LightshadeClient").log(System.Logger.Level.WARNING,
                    "Lightshade could not rebuild after resource reload; native rendering remains active.", failure);
                return null;
            })));
    }
}
