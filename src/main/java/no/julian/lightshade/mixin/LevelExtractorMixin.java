package no.julian.lightshade.mixin;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.render.LightshadeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** An entity just out of view still throws its shadow into view: keep near ones in the frame, for the shadow pass. */
@Mixin(value=LevelExtractor.class, remap=false)
public abstract class LevelExtractorMixin {
 @Inject(method="isEntityVisible",at=@At("RETURN"),cancellable=true)
 private void lightshade$shadowCaster(Entity entity,Frustum frustum,double camX,double camY,double camZ,float partialTicks,long chunkFadeDuration,CallbackInfoReturnable<Boolean> result){
  if(!result.getReturnValueZ()&&LightshadeClient.allowed()&&LightshadeRenderer.castsShadowOutOfView(entity,camX,camY,camZ))result.setReturnValue(true);
 }
}
