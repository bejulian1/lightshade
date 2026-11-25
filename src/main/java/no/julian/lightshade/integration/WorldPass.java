package no.julian.lightshade.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import java.nio.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import no.julian.lightshade.render.LightshadeRenderer;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;

/** One owner across the opaque/transparent boundary. Transfer commands are issued
 * only after closing the opaque pass, then the transparent pass loads its pixels.
 * This preserves native classic transparency without requiring OIT. */
public final class WorldPass implements RenderPass {
    private RenderPass pass;
    private final CommandEncoder encoder;
    private final GpuTextureView color,depth;
    private boolean split,closed;
    public WorldPass(CommandEncoder encoder,Supplier<String> label,GpuTextureView color,Optional<Vector4fc> clear,GpuTextureView depth,OptionalDouble depthClear){
        this.encoder=encoder;this.color=color;this.depth=depth;
        pass=encoder.createRenderPass(label,color,clear,depth,depthClear);
    }
    public void beginTransparency(){
        if(split||closed||!LightshadeRenderer.enabled())return;
        var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if(target.getColorTextureView()!=color||target.getDepthTextureView()!=depth)return;
        split=true;pass.close();
        LightshadeRenderer.captureOpaque(target);
        pass=encoder.createRenderPass(()->"Lightshade classic transparency",color,Optional.empty(),depth,OptionalDouble.empty());
        RenderSystem.bindDefaultUniforms(pass);
    }
    @Override public void close(){if(!closed){closed=true;pass.close();}}
    @Override public void pushDebugGroup(Supplier<String> n){pass.pushDebugGroup(n);}
    @Override public void popDebugGroup(){pass.popDebugGroup();}
    @Override public void writeTimestamp(GpuQueryPool q,int i){pass.writeTimestamp(q,i);}
    @Override public void setPipeline(CompiledRenderPipeline p){pass.setPipeline(p);}
    @Override public void setUniform(String n,GpuTextureView v,GpuSampler s){pass.setUniform(n,v,s);}
    @Override public void setUniform(String n,GpuBuffer b){pass.setUniform(n,b);}
    @Override public void setUniform(String n,GpuBufferSlice b){pass.setUniform(n,b);}
    @Override public void pushConstants(ByteBuffer b){pass.pushConstants(b);}
    @Override public void enableScissor(int x,int y,int w,int h){pass.enableScissor(x,y,w,h);}
    @Override public void disableScissor(){pass.disableScissor();}
    @Override public void setVertexBuffer(int i,GpuBufferSlice b){pass.setVertexBuffer(i,b);}
    @Override public void setIndexBuffer(GpuBuffer b,IndexType t){pass.setIndexBuffer(b,t);}
    @Override public void drawIndexed(int a,int b,int c,int d,int e){pass.drawIndexed(a,b,c,d,e);}
    @Override public void multiDrawIndexed(IntBuffer a,int b,int c,int d){pass.multiDrawIndexed(a,b,c,d);}
    @Override public void multiDrawIndexed(PointerBuffer a,IntBuffer b,IntBuffer c,int d){pass.multiDrawIndexed(a,b,c,d);}
    @Override public void drawIndexedIndirect(GpuBufferSlice a,int b){pass.drawIndexedIndirect(a,b);}
    @Override public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> a,GpuBuffer b,IndexType c,Collection<String>d,T e){pass.drawMultipleIndexed(a,b,c,d,e);}
    @Override public void draw(int a,int b,int c,int d){pass.draw(a,b,c,d);}
    @Override public void multiDraw(IntBuffer a,int b,int c,int d){pass.multiDraw(a,b,c,d);}
    @Override public void multiDraw(IntBuffer a,IntBuffer b,int c){pass.multiDraw(a,b,c);}
    @Override public void drawIndirect(GpuBufferSlice a,int b){pass.drawIndirect(a,b);}
}
