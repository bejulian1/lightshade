package no.julian.lightshade.mixin;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.render.LightshadeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(value=DefaultChunkRenderer.class, remap=false)
public abstract class SodiumRenderMixin {
 @Redirect(method="render", at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
 private void lightshade$bind(RenderPass pass, CompiledRenderPipeline pipeline) {
  pass.setPipeline(pipeline);
  if(LightshadeClient.allowed()&&LightshadeRenderer.enabled())LightshadeRenderer.bindTerrain(pass);
 }
}
