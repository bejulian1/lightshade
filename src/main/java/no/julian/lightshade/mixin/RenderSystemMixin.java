package no.julian.lightshade.mixin;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.render.LightshadeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=RenderSystem.class, remap=false)
public abstract class RenderSystemMixin {
 @Inject(method="getCompiledPipelineNullable", at=@At("HEAD"), cancellable=true)
 private static void lightshade$terrain(RenderPipeline original, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
  if (!LightshadeClient.allowed()) return;
  var replacement=LightshadeRenderer.replacement(original);
  if(replacement!=null)cir.setReturnValue(replacement);
 }
 @Inject(method="getCompiledPipelineNullable",at=@At("RETURN"))
 private static void lightshade$observe(RenderPipeline original,CallbackInfoReturnable<CompiledRenderPipeline> cir){
  if(LightshadeClient.allowed()&&cir.getReturnValue()!=null)LightshadeRenderer.observeCompiled(original,cir.getReturnValue());
 }
}
