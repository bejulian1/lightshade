package no.julian.lightshade.mixin;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.render.LightshadeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** A pack that draws its own rain ("ownsRain") removes vanilla's rain columns. Snow stays. */
@Mixin(value=WeatherEffectRenderer.class,remap=false)
public abstract class WeatherEffectRendererMixin {
 @Inject(method="extractRenderState",at=@At("RETURN"))
 private void lightshade$noRain(ClientLevel level,float partial,Vec3 camera,WeatherRenderState state,CallbackInfo ci){
  if(LightshadeClient.allowed()&&LightshadeRenderer.ownsRain())state.rainColumns.clear();
 }
}
