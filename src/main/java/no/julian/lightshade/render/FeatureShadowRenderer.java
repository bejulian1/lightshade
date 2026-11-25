package no.julian.lightshade.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.PointerBuffer;
import java.nio.*;
import java.util.*;
import java.util.function.Supplier;

/** Replays current prepared solid model draws with original depth-only programs. */
final class FeatureShadowRenderer implements AutoCloseable {
    private static final BindGroupLayout FRAME=BindGroupLayout.builder().withUniform("LsFeatureShadow",UniformType.UNIFORM_BUFFER).build();
    private final PackShaderSource sources;
    private final long generation;
    private final Map<RenderPipeline,CompiledRenderPipeline> pipelines=new IdentityHashMap<>();
    private final Set<String> families=new TreeSet<>();
    private long frames,draws,indices,skipped;
    FeatureShadowRenderer(PackShaderSource sources,long generation){this.sources=sources;this.generation=generation;}
    void begin(){draws=0;indices=0;skipped=0;frames++;}
    /** Once per cascade: the prepared draws replay against that cascade's map and matrix. */
    void render(FeatureRenderDispatcher.PreparedFrame prepared,GpuTextureView depth,Matrix4fc light,Matrix4fc view){
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        ByteBuffer data=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        new Matrix4f(light).mul(new Matrix4f(view).invert()).get(0,data);
        GpuBufferSlice uniform=encoder.transientMemory().uploadGpu(data,Math.max(16,RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment()),GpuBuffer.USAGE_UNIFORM);
        var descriptor=RenderPassDescriptor.builder(()->"Lightshade prepared model shadows").withDepthAttachment(depth).build();
        try(RenderPass pass=encoder.createRenderPass(descriptor)){prepared.executeSolid(new FilteredPass(pass,uniform));}
        // Prepared feature buffers must remain valid for the subsequent scene pass.
        // RenderPass.close records the pass; native frame submission owns its lifetime.
    }
    private CompiledRenderPipeline replacement(CompiledRenderPipeline compiled){
        RenderPipeline original=LightshadeRenderer.nativeDescriptor(compiled);if(original==null)return null;
        Identifier vertex=original.getShaders().get(ShaderType.VERTEX);
        if(vertex==null||!vertex.getNamespace().equals("minecraft")||!Set.of("core/entity","core/block","core/item").contains(vertex.getPath()))return null;
        if(original.getVertexFormatBindings().isEmpty()||original.getVertexFormatBindings().getFirst()==null)return null;
        var format=original.getVertexFormatBindings().getFirst();
        if(!format.contains("Position")||!format.contains("UV0")||!format.contains("Color"))return null;
        CompiledRenderPipeline cached=pipelines.get(original);if(cached!=null)return cached;
        String path="features/g"+generation+"/p"+pipelines.size();
        Identifier vs=Identifier.fromNamespaceAndPath("lightshade",path+"_vertex"),fs=Identifier.fromNamespaceAndPath("lightshade",path+"_fragment");
        sources.put(vs,VERTEX);sources.put(fs,FRAGMENT);
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("lightshade",path)).withVertexShader(vs).withFragmentShader(fs)
            .withCull(false).withPrimitiveTopology(original.getPrimitiveTopology()).withPushConstantSize(original.pushConstantSize())
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL,true));
        for(int slot=0;slot<original.getVertexFormatBindings().size();slot++)if(original.getVertexFormatBindings().get(slot)!=null)builder.withVertexBinding(slot,original.getVertexFormatBindings().get(slot));
        original.getBindGroupLayouts().forEach(builder::withBindGroupLayout);builder.withBindGroupLayout(FRAME);
        if(vertex.getPath().equals("core/block"))builder.withShaderDefine("LS_MODEL_OFFSET");
        if(original.getShaderDefines().flags().contains("APPLY_TEXTURE_MATRIX"))builder.withShaderDefine("APPLY_TEXTURE_MATRIX");
        CompiledRenderPipeline result=RenderSystem.getDevice().compilePipeline(builder.build(),sources,Runnable::run).join().finishCompile();
        if(result==null)throw new IllegalStateException("Failed model shadow pipeline "+original.getLocation());
        pipelines.put(original,result);families.add(vertex.toString());return result;
    }
    long draws(){return draws;}
    Map<String,Object> status(){return Map.of("frames",frames,"draws",draws,"indices",indices,"skippedPipelines",skipped,"pipelines",pipelines.size(),"families",List.copyOf(families),"casterScope","current prepared solid entity, block-model and item features; entities out of view count within "+ShadowMaps.OFFSCREEN_CASTERS+" blocks, block entities only when in view");}
    @Override public void close(){pipelines.values().forEach(CompiledRenderPipeline::close);pipelines.clear();}
    private final class FilteredPass implements RenderPass {
        private final RenderPass target;private final GpuBufferSlice uniform;private boolean selected;
        FilteredPass(RenderPass target,GpuBufferSlice uniform){this.target=target;this.uniform=uniform;}
        public void setPipeline(CompiledRenderPipeline original){var pipeline=replacement(original);selected=pipeline!=null;if(selected){target.setPipeline(pipeline);target.setUniform("LsFeatureShadow",uniform);}else skipped++;}
        public void setUniform(String name,GpuTextureView view,GpuSampler sampler){if(selected)target.setUniform(name,view,sampler);}
        public void setUniform(String name,GpuBuffer buffer){if(selected)target.setUniform(name,buffer);}
        public void setUniform(String name,GpuBufferSlice buffer){if(selected)target.setUniform(name,buffer);}
        public void pushConstants(ByteBuffer bytes){if(selected)target.pushConstants(bytes);}
        public void setVertexBuffer(int slot,GpuBufferSlice vertices){if(selected)target.setVertexBuffer(slot,vertices);}
        public void setIndexBuffer(GpuBuffer index,IndexType type){if(selected)target.setIndexBuffer(index,type);}
        public void drawIndexed(int count,int instances,int first,int offset,int instance){if(selected){target.drawIndexed(count,instances,first,offset,instance);draws++;indices+=(long)count*instances;}}
        public void draw(int count,int instances,int first,int instance){if(selected){target.draw(count,instances,first,instance);draws++;indices+=(long)count*instances;}}
        public void multiDrawIndexed(IntBuffer commands,int instances,int firstInstance,int count){if(selected){target.multiDrawIndexed(commands,instances,firstInstance,count);draws+=count;}}
        public void multiDrawIndexed(PointerBuffer offsets,IntBuffer counts,IntBuffer bases,int count){if(selected){target.multiDrawIndexed(offsets,counts,bases,count);draws+=count;}}
        public void drawIndexedIndirect(GpuBufferSlice commands,int count){if(selected){target.drawIndexedIndirect(commands,count);draws+=count;}}
        public void multiDraw(IntBuffer commands,int instances,int firstInstance,int count){if(selected){target.multiDraw(commands,instances,firstInstance,count);draws+=count;}}
        public void multiDraw(IntBuffer first,IntBuffer counts,int count){if(selected){target.multiDraw(first,counts,count);draws+=count;}}
        public void drawIndirect(GpuBufferSlice commands,int count){if(selected){target.drawIndirect(commands,count);draws+=count;}}
        public <T> void drawMultipleIndexed(Collection<Draw<T>> commands,GpuBuffer index,IndexType type,Collection<String> names,T context){
            if(!selected)return;
            UniformUploader uploader=new UniformUploader(){public void setUniform(String name,GpuBufferSlice value){FilteredPass.this.setUniform(name,value);}public void pushConstants(ByteBuffer bytes){FilteredPass.this.pushConstants(bytes);}};
            for(Draw<T> command:commands){setVertexBuffer(command.slot(),command.vertexBuffer().slice());setIndexBuffer(command.indexBuffer()==null?index:command.indexBuffer(),command.indexType()==null?type:command.indexType());if(command.uniformUploaderConsumer()!=null)command.uniformUploaderConsumer().accept(context,uploader);drawIndexed(command.indexCount(),1,command.firstIndex(),command.baseVertex(),0);}
        }
        // Screen-space scissors from the scene camera do not describe the light camera.
        public void enableScissor(int x,int y,int width,int height){}public void disableScissor(){}
        public void pushDebugGroup(Supplier<String> label){target.pushDebugGroup(label);}public void popDebugGroup(){target.popDebugGroup();}
        public void writeTimestamp(GpuQueryPool pool,int query){}
        public void close(){} // The containing try-with-resources owns the native pass.
    }
    static final String VERTEX="""
        #version 450
        #include <minecraft:dynamictransforms.glsl>
        layout(std140) uniform LsFeatureShadow {mat4 LsFeatureLight;};
        layout(location=0) in vec3 Position;layout(location=1) in vec4 Color;layout(location=2) in vec2 UV0;
        layout(location=0) out vec2 shadowUV;layout(location=1) out float shadowAlpha;
        void main(){vec3 p=Position;
            #ifdef LS_MODEL_OFFSET
            p+=ModelOffset;
            #endif
            gl_Position=LsFeatureLight*ModelViewMat*vec4(p,1.0);shadowUV=UV0;shadowAlpha=Color.a*ColorModulator.a;
            #ifdef APPLY_TEXTURE_MATRIX
            shadowUV=(TextureMat*vec4(UV0,0.0,1.0)).xy;
            #endif
        }
        """;
    static final String FRAGMENT="""
        #version 450
        layout(location=0) in vec2 shadowUV;layout(location=1) in float shadowAlpha;
        uniform sampler2D Sampler0;
        void main(){if(texture(Sampler0,shadowUV).a*shadowAlpha<.1)discard;}
        """;
}
