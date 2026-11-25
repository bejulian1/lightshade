package no.julian.lightshade.mixin;
import net.minecraft.client.renderer.CloudRenderer;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.render.LightshadeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=CloudRenderer.class,remap=false)
public abstract class CloudRendererMixin {
 @Inject(method="render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",at=@At("HEAD"),cancellable=true)
 private void lightshade$clouds(CallbackInfo ci){if(LightshadeClient.allowed()&&LightshadeRenderer.ownsSky())ci.cancel();}
 @Inject(method="renderOit",at=@At("HEAD"),cancellable=true)
 private void lightshade$cloudsOit(CallbackInfo ci){if(LightshadeClient.allowed()&&LightshadeRenderer.ownsSky())ci.cancel();}
}
