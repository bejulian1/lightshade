package no.julian.lightshade.mixin;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import no.julian.lightshade.LightshadeClient;
import no.julian.lightshade.render.LightshadeRenderer;
import no.julian.lightshade.integration.WorldPass;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LevelRenderer.class, remap=false)
public abstract class LevelRendererMixin {
 @Inject(method="render",at=@At("HEAD"))
 private void lightshade$frame(GraphicsResourceAllocator allocator,boolean outline,CameraRenderState camera,GpuBufferSlice fog,Vector4f color,boolean sky,boolean effects,CallbackInfo ci){
  if(LightshadeClient.allowed())LightshadeRenderer.updateFrame(camera.projectionMatrix,camera.viewRotationMatrix);
 }
 @Redirect(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
 private RenderPass lightshade$worldPass(CommandEncoder encoder,Supplier<String> label,GpuTextureView color,Optional<Vector4fc> clear,GpuTextureView depth,OptionalDouble depthClear){
  return LightshadeClient.allowed()&&LightshadeRenderer.enabled()?new WorldPass(encoder,label,color,clear,depth,depthClear):encoder.createRenderPass(label,color,clear,depth,depthClear);
 }
 @Inject(method="executeClassicTransparency",at=@At("HEAD"))
 private void lightshade$classicOpaque(ChunkSectionsToRender sections,FeatureRenderDispatcher.PreparedFrame features,RenderPass pass,CallbackInfo ci){
  if(pass instanceof WorldPass world)world.beginTransparency();
 }
 @Inject(method="lambda$addMainPass$0",at=@At("HEAD"))
 private void lightshade$featureShadows(GpuBufferSlice fog,boolean outline,ChunkSectionsToRender sections,FeatureRenderDispatcher.PreparedFrame features,boolean a,boolean b,CallbackInfo ci){
  if(LightshadeClient.allowed())LightshadeRenderer.renderFeatureShadows(features);
 }
 // The opaque render pass has closed at this stage. Copying earlier from
 // executeSolid would issue a transfer inside an active Vulkan render pass.
 @Inject(method="executeOit",at=@At("HEAD"))
 private void lightshade$opaque(CallbackInfo ci){
  if(LightshadeClient.allowed())LightshadeRenderer.captureOpaque(Minecraft.getInstance().gameRenderer.mainRenderTarget());
 }
 @Inject(method="render",at=@At("RETURN"))
 private void lightshade$display(CallbackInfo ci){
  if(LightshadeClient.allowed())LightshadeRenderer.composite(Minecraft.getInstance().gameRenderer.mainRenderTarget());
 }
}
