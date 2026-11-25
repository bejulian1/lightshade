package no.julian.lightshade.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.lang.reflect.Field;
import java.nio.*;
import java.util.*;

/** Fresh per-frame geometry reads: never retains offsets, cached batches or camera visibility.
 * One to three cascades: each is its own depth map along the same light with the same depth range, so a pack
 * can read depths across them. The last cascade is the full shadow range and is bound as LsShadowMap. */
final class ShadowMaps implements AutoCloseable {
    private static final BindGroupLayout MATERIAL_LAYOUT=BindGroupLayout.builder().withUniform("LsMaterials",UniformType.COMBINED_IMAGE_SAMPLER).build();
    static final BindGroupLayout RECEIVER_LAYOUT=BindGroupLayout.builder().withUniform("LsShadowFrame",UniformType.UNIFORM_BUFFER).withUniform("LsShadowMap",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("LsShadowMid",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("LsShadowNear",UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final int MAX_CASCADES=3,RECEIVER_BYTES=64+16+16+MAX_CASCADES*16,CASTER_BYTES=80;
    /** The nearest cascade's half width in blocks, before it is limited by the full range. */
    private static final float NEAREST_RADIUS=24;
    /** The light's distance from the camera: casters this far toward the sun still count, whatever the range. */
    private static final float MINIMUM_REACH=288;
    private static final double ANCHOR=64;
    /** Entities out of view still count as casters this close to the camera, in blocks. */
    static final double OFFSCREEN_CASTERS=40;
    private static final Field SECTION_MANAGER;
    static{try{SECTION_MANAGER=SodiumWorldRenderer.class.getDeclaredField("renderSectionManager");SECTION_MANAGER.setAccessible(true);}catch(ReflectiveOperationException error){throw new ExceptionInInitializerError(error);}}
    private record Draw(int baseVertex,int indexCount){}
    private record RegionDraw(RenderRegion region,GpuBuffer vertices,List<Draw> draws){}
    private final CompiledRenderPipeline pipeline,solidPipeline;
    private final PackShaderSource packSources;
    private final FeatureShadowRenderer features;
    private final Matrix4f lightMatrix=new Matrix4f();
    private final int cascades;
    private final Matrix4f[] matrices;
    private final float[] radii;
    private final GpuTexture[] depth;
    private final GpuTextureView[] depthView;
    private final long[] cascadeDraws;
    private final int size;
    private final float radius,reach;
    private final double tilt;
    private GpuBufferSlice uniform;
    private long frames,draws,indices,invalidRanges,drawCalls;
    private boolean ready,live;
    /** Reused from frame to frame: with several maps a fresh direct buffer per region adds up to GC pauses. */
    private IntBuffer commands=ByteBuffer.allocateDirect(3072).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final Vector3f lastDirection=new Vector3f(0,1,0);
    private float lastWeight;
    /** SHADOWS=0: the map is never drawn and receivers see full sun. */
    private final boolean enabled;

    /** A pack may bring its own shadow vertex program (manifest "shadow": {"vertex": …}), e.g. to wave plants
     * like its terrain does; it then also gets the frame block and the material map. */
    private final boolean packVertex;
    ShadowMaps(PackShaderSource sources,long generation,Map<String,Double> options,long budget,String vertexSource){
        packVertex=vertexSource!=null;packSources=sources;
        int requested=options.getOrDefault("SHADOW_RESOLUTION",2048.0).intValue();
        size=Math.min(Math.max(512,requested),RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.D32_FLOAT));
        enabled=options.getOrDefault("SHADOWS",1.0)>0;
        radius=options.getOrDefault("SHADOW_DISTANCE",96.0).floatValue();
        if(!Float.isFinite(radius)||radius<32||radius>256)throw new IllegalArgumentException("Shadow distance must be 32..256");
        cascades=Math.clamp(options.getOrDefault("SHADOW_CASCADES",1.0).intValue(),1,MAX_CASCADES);
        if(memoryBytes()>budget)throw new IllegalArgumentException("Shadow allocation exceeds pack memory budget");
        reach=Math.max(radius*2,MINIMUM_REACH);
        tilt=Math.toRadians(options.getOrDefault("SUN_TILT",0.0));
        radii=new float[cascades];matrices=new Matrix4f[cascades];depth=new GpuTexture[cascades];depthView=new GpuTextureView[cascades];cascadeDraws=new long[cascades];
        // Each cascade an even step wider than the last, from a nearest one that stays sharp to the full range.
        float nearest=Math.min(NEAREST_RADIUS,radius/2.5f);
        for(int i=0;i<cascades;i++){radii[i]=cascades==1?radius:(float)(nearest*Math.pow(radius/nearest,i/(cascades-1.0)));matrices[i]=new Matrix4f();}
        radii[cascades-1]=radius;
        Identifier vertex=Identifier.fromNamespaceAndPath("lightshade","shadow/g"+generation+"/vertex"),fragment=Identifier.fromNamespaceAndPath("lightshade","shadow/g"+generation+"/fragment");
        sources.put(vertex,packVertex?vertexSource:VERTEX);sources.put(fragment,FRAGMENT);
        Identifier solidFragment=Identifier.fromNamespaceAndPath("lightshade","shadow/g"+generation+"/solid_fragment");
        sources.put(solidFragment,SOLID_FRAGMENT);
        // Most of a map is solid blocks. They need no texture and no discard, and so keep the GPU's early depth test.
        solidPipeline=compile("solid",generation,vertex,solidFragment,options);
        pipeline=compile("cutout",generation,vertex,fragment,options);
        for(int i=0;i<cascades;i++){
            depth[i]=RenderSystem.getDevice().createTexture("Lightshade live directional shadow "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC,GpuFormat.D32_FLOAT,size,size,1,1);
            depthView[i]=RenderSystem.getDevice().createTextureView(depth[i]);
        }
        features=new FeatureShadowRenderer(sources,generation);
    }
    /** Direction to a body on Minecraft's sky arc. Left on that arc the sun runs straight overhead, exactly
     * edge-on to every north and south face all day, so a pack may lean the whole arc south (SUN_TILT, degrees). */
    static Vector3f celestial(float degrees,double tiltRadians){
        double angle=Math.toRadians(degrees),height=Math.cos(angle);
        return new Vector3f((float)-Math.sin(angle),(float)(height*Math.cos(tiltRadians)),(float)(height*Math.sin(tiltRadians)));
    }
    double sunTilt(){return tilt;}
    boolean casts(){return live;}
    private CompiledRenderPipeline compile(String name,long generation,Identifier vertex,Identifier fragment,Map<String,Double> options){
        var layout=BindGroupLayout.builder().withUniform("LsShadowFrame",UniformType.UNIFORM_BUFFER).withUniform("ShadowAtlas",UniformType.COMBINED_IMAGE_SAMPLER).build();
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("lightshade","shadow/g"+generation+"/terrain_"+name))
            .withVertexShader(vertex).withFragmentShader(fragment).withShaderDefine("USE_VERTEX_COMPRESSION")
            .withBindGroupLayout(layout).withVertexBinding(0,ChunkMeshFormats.getCurrent().getVertexFormat())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withPushConstantSize(16).withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL,true));
        if(packVertex){
            builder.withBindGroupLayout(LightshadeRenderer.FRAME_LAYOUT).withBindGroupLayout(MATERIAL_LAYOUT);
            options.forEach((key,value)->{if(value==Math.rint(value))builder.withShaderDefine(key,value.intValue());else builder.withShaderDefine(key,value.floatValue());});
        }
        CompiledRenderPipeline compiled=RenderSystem.getDevice().compilePipeline(builder.build(),packSources,Runnable::run).join().finishCompile();
        if(compiled==null)throw new IllegalStateException("Lightshade shadow shader failed to compile");
        return compiled;
    }
    private static int alignment(){return Math.max(16,RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());}
    /** What every receiver reads. Depth range and matrix come from the outermost cascade. */
    private GpuBufferSlice uploadReceiver(boolean visible,Vector3f toLight,float weight){
        ByteBuffer data=ByteBuffer.allocateDirect(RECEIVER_BYTES).order(ByteOrder.nativeOrder());lightMatrix.get(0,data);data.position(64);
        data.putFloat(visible?1:0).putFloat(1f/size).putFloat(radius).putFloat(reach*2-.1f);
        data.putFloat(toLight.x).putFloat(toLight.y).putFloat(toLight.z).putFloat(weight);
        Vector3f origin=new Vector3f();
        for(int i=0;i<MAX_CASCADES;i++){
            if(i<cascades){
                // A point's clip position in this cascade is scale*(outer clip)+offset: same light, other size and snap.
                float scale=radius/radii[i];matrices[i].transformPosition(origin.zero());Vector3f outer=lightMatrix.transformPosition(new Vector3f());
                data.putFloat(scale).putFloat(origin.x-outer.x*scale).putFloat(origin.y-outer.y*scale).putFloat(2*radii[i]/size);
            }else data.putFloat(1).putFloat(0).putFloat(0).putFloat(2*radius/size);
        }
        data.flip();
        return RenderSystem.getDevice().createCommandEncoder().transientMemory().uploadGpu(data,alignment(),GpuBuffer.USAGE_UNIFORM);
    }
    void render(Vec3 camera,float sunAngle,GpuBufferSlice frameBlock,GpuTextureView materials)throws ReflectiveOperationException{
        ready=false;draws=0;indices=0;invalidRanges=0;drawCalls=0;Arrays.fill(cascadeDraws,0);
        var level=Minecraft.getInstance().level;
        // Only the overworld's terrain reads a sun or moon; elsewhere the maps would be drawn for nobody.
        live=enabled&&level!=null&&level.dimension().equals(Level.OVERWORLD);
        if(!live){lightMatrix.identity();uniform=uploadReceiver(false,new Vector3f(0,1,0),0);return;}
        Vector3f direction=celestial(sunAngle,tilt);
        if(direction.y<0)direction.negate();
        // The light hands over from sun to moon at the horizon: it counts for nothing just there, so nothing jumps.
        float altitude=direction.y,weight=altitude<=.004f?0:altitude>=.05f?1:(float)Math.pow((altitude-.004f)/.046f,2)*(3-2*(altitude-.004f)/.046f);
        // Under cloud the sky lights everything from all sides: shadows thin out with the rain.
        weight*=1-.8f*level.getRainLevel(1);
        var lightView=new Matrix4f().lookAt(new Vector3f(direction).mul(reach),new Vector3f(),new Vector3f(0,0,1));
        // Lock each map's texel grid to the world: the camera moving does not move the grid, so edges do not swim.
        // The lock is measured from a nearby anchor, not the world origin: as the sun turns the light's axes, a
        // grid hinged at the origin would slide further the further from it you are.
        Vector3f anchored=lightView.transformDirection(new Vector3f((float)(camera.x-Math.floor(camera.x/ANCHOR)*ANCHOR),(float)(camera.y-Math.floor(camera.y/ANCHOR)*ANCHOR),(float)(camera.z-Math.floor(camera.z/ANCHOR)*ANCHOR)));
        float[] marginX=new float[cascades],marginY=new float[cascades],marginZ=new float[cascades];
        for(int i=0;i<cascades;i++){
            float half=radii[i],texel=2*half/size;
            Matrix4f view=new Matrix4f(lightView);
            view.m30(view.m30()+(anchored.x-Math.round(anchored.x/texel)*texel));
            view.m31(view.m31()+(anchored.y-Math.round(anchored.y/texel)*texel));
            Matrix4f light=matrices[i].identity().ortho(-half,half,-half,half,.1f,reach*2,true).mul(view);
            marginX[i]=9*(Math.abs(light.m00())+Math.abs(light.m10())+Math.abs(light.m20()));
            marginY[i]=9*(Math.abs(light.m01())+Math.abs(light.m11())+Math.abs(light.m21()));
            marginZ[i]=9*(Math.abs(light.m02())+Math.abs(light.m12())+Math.abs(light.m22()));
        }
        lightMatrix.set(matrices[cascades-1]);
        Vector3f center=new Vector3f();
        var renderer=SodiumWorldRenderer.instanceNullable();
        RenderSectionManager manager=renderer==null?null:(RenderSectionManager)SECTION_MANAGER.get(renderer);
        // Per layer, per cascade: solid blocks first, so cutouts behind them are rejected without being shaded.
        List<List<List<RegionDraw>>> plan=new ArrayList<>();
        for(int layer=0;layer<2;layer++){plan.add(new ArrayList<>());for(int i=0;i<cascades;i++)plan.get(layer).add(new ArrayList<>());}
        int maximumIndices=0,stride=ChunkMeshFormats.getCurrent().getVertexFormat().getVertexSize();
        if(manager!=null)for(RenderRegion region:manager.regions.getLoadedRegions()){
            var resources=region.getResources();if(resources==null)continue;GpuBuffer geometry=resources.getGeometryBuffer();if(geometry==null||geometry.isClosed())continue;
            for(int pass=0;pass<2;pass++){
                var storage=region.getStorage(pass==0?DefaultTerrainRenderPasses.SOLID:DefaultTerrainRenderPasses.CUTOUT);if(storage==null)continue;
                List<List<Draw>> sections=new ArrayList<>();for(int i=0;i<cascades;i++)sections.add(new ArrayList<>());
                for(int section=0;section<256;section++){
                    long address=storage.getDataPointer(section),count=0;
                    for(int face=0;face<7;face++)count+=SectionRenderDataUnsafe.getVertexCount(address,face);
                    if(count==0)continue;long base=SectionRenderDataUnsafe.getBaseVertex(address);
                    if(count%4!=0||count>Integer.MAX_VALUE/6*4L||base<0||base>Integer.MAX_VALUE||(base+count)*stride>geometry.size()){invalidRanges++;continue;}
                    float x=(float)(region.getOriginX()+((section>>5)&7)*16+8-camera.x),y=(float)(region.getOriginY()+(section&3)*16+8-camera.y),z=(float)(region.getOriginZ()+((section>>2)&7)*16+8-camera.z);
                    int elements=(int)(count/4*6);
                    for(int i=0;i<cascades;i++){
                        matrices[i].transformPosition(center.set(x,y,z));
                        if(Math.abs(center.x)>1+marginX[i]||Math.abs(center.y)>1+marginY[i]||center.z < -marginZ[i]||center.z > 1+marginZ[i])continue;
                        maximumIndices=Math.max(maximumIndices,elements);sections.get(i).add(new Draw((int)base,elements));
                    }
                }
                for(int i=0;i<cascades;i++)if(!sections.get(i).isEmpty())plan.get(pass).get(i).add(new RegionDraw(region,geometry,sections.get(i)));
            }
        }
        boolean any=plan.stream().anyMatch(layer->layer.stream().anyMatch(list->!list.isEmpty()));
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        uniform=uploadReceiver(any,direction,weight);
        var sequential=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);GpuBuffer indexBuffer=maximumIndices>0?sequential.getBuffer(maximumIndices):null;
        for(int cascade=0;cascade<cascades;cascade++){
            ByteBuffer frame=ByteBuffer.allocateDirect(CASTER_BYTES).order(ByteOrder.nativeOrder());matrices[cascade].get(0,frame);frame.position(64);frame.putFloat(any?1:0).putFloat(1f/size).putFloat(radii[cascade]).putFloat(reach*2-.1f);frame.flip();
            GpuBufferSlice caster=encoder.transientMemory().uploadGpu(frame,alignment(),GpuBuffer.USAGE_UNIFORM);
            var descriptor=RenderPassDescriptor.builder(()->"Lightshade live terrain shadows").withDepthAttachment(depthView[cascade],OptionalDouble.of(1)).build();
            try(RenderPass pass=encoder.createRenderPass(descriptor)){
                if(indexBuffer!=null)for(int layer=0;layer<2;layer++){
                    List<RegionDraw> regions=plan.get(layer).get(cascade);if(regions.isEmpty())continue;
                    pass.setPipeline(layer==0?solidPipeline:pipeline);pass.setUniform("LsShadowFrame",caster);
                    if(packVertex){pass.setUniform("LsFrame",frameBlock);pass.setUniform("LsMaterials",materials,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));}
                    var atlas=Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
                    pass.setUniform("ShadowAtlas",atlas.getTextureView(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.setIndexBuffer(indexBuffer,sequential.type());
                    drawRegions(pass,regions,cascade,camera);
                }
            }
        }
        // The native frame owns submission. Submitting here expires both this
        // receiver uniform and native transient buffers prepared for this frame.
        ready=draws>0;frames++;
        lastDirection.set(direction);lastWeight=weight;
    }
    private void drawRegions(RenderPass pass,List<RegionDraw> regions,int cascade,Vec3 camera){
        ByteBuffer push=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        var info=RenderSystem.getDevice().getDeviceInfo();
        for(RegionDraw region:regions){
            pass.setVertexBuffer(0,region.vertices.slice());push.clear();
            push.putFloat((float)(region.region.getOriginX()-camera.x)).putFloat((float)(region.region.getOriginY()-camera.y)).putFloat((float)(region.region.getOriginZ()-camera.z)).putFloat(0).flip();
            pass.pushConstants(push);
            if(info.features().multiDrawDirectInterleaved()){
                int limit=Math.max(1,info.limits().maxMultiDrawDirectInterleavedDrawCount());
                if(commands.capacity()<Math.min(limit,region.draws.size())*3)commands=ByteBuffer.allocateDirect(Math.min(limit,region.draws.size())*24).order(ByteOrder.nativeOrder()).asIntBuffer();
                for(int offset=0;offset<region.draws.size();){
                    commands.clear();int count=Math.min(limit,region.draws.size()-offset);
                    for(int index=0;index<count;index++){Draw draw=region.draws.get(offset+index);commands.put(0).put(draw.indexCount).put(draw.baseVertex);draws++;cascadeDraws[cascade]++;indices+=draw.indexCount;}
                    commands.flip();pass.multiDrawIndexed(commands,1,0,count);drawCalls++;offset+=count;
                }
            }else for(Draw draw:region.draws){pass.drawIndexed(draw.indexCount,1,0,draw.baseVertex,0);draws++;cascadeDraws[cascade]++;indices+=draw.indexCount;drawCalls++;}
        }
    }
    void bind(RenderPass pass){
        if(uniform==null)throw new IllegalStateException("Shadow frame not prepared");
        if(uniform.buffer().isClosed())throw new IllegalStateException("Shadow frame expired before receiver rendering");
        var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform("LsShadowFrame",uniform);
        pass.setUniform("LsShadowMap",depthView[cascades-1],sampler);
        pass.setUniform("LsShadowMid",depthView[Math.min(1,cascades-1)],sampler);
        pass.setUniform("LsShadowNear",depthView[0],sampler);
    }
    Map<String,Object> status(){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",enabled);result.put("ready",ready);result.put("frames",frames);result.put("draws",draws);result.put("drawCalls",drawCalls);result.put("indices",indices);result.put("invalidRanges",invalidRanges);
        result.put("resolution",size);result.put("distance",radius);result.put("cascades",cascades);result.put("cascadeRadii",radiiList());result.put("cascadeDraws",Arrays.stream(cascadeDraws).boxed().toList());result.put("features",features.status());
        return result;
    }
    private List<Float> radiiList(){List<Float> list=new ArrayList<>();for(float value:radii)list.add(value);return list;}
    void renderFeatures(net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame prepared,org.joml.Matrix4fc view){
        if(!live)return;
        features.begin();
        for(int i=0;i<cascades;i++)features.render(prepared,depthView[i],matrices[i],view);
        if(!ready&&features.draws()>0){ready=true;uniform=uploadReceiver(true,lastDirection,lastWeight);}
    }
    /** The full-range map, for a pack's graph input "$shadow". */
    GpuTextureView textureView(){return depthView[cascades-1];}
    long memoryBytes(){return (long)size*size*4*cascades;}
    @Override public void close(){features.close();pipeline.close();solidPipeline.close();for(int i=0;i<cascades;i++){if(depthView[i]!=null)depthView[i].close();if(depth[i]!=null)depth[i].close();}}
    private static final String VERTEX="""
        #version 450
        #include <sodium:chunk_vertex.glsl>
        layout(std140) uniform LsShadowFrame {mat4 LsLightMatrix;vec4 LsShadowInfo;};
        layout(push_constant) uniform ShadowRegion {vec4 ShadowRegionOffset;};
        layout(location=0) out vec2 shadowUV;layout(location=1) out float shadowAlpha;layout(location=2) flat out uint shadowFlags;
        void main(){_vert_init();uvec3 section=(uvec3(_draw_id)>>uvec3(5,0,2))&uvec3(7,3,7);vec3 p=_vert_position+vec3(section)*16.0+ShadowRegionOffset.xyz;gl_Position=LsLightMatrix*vec4(p,1);shadowUV=_vert_tex_diffuse_coord;shadowAlpha=_vert_color.a;shadowFlags=_material_params;}
        """;
    private static final String SOLID_FRAGMENT="""
        #version 450
        void main(){}
        """;
    private static final String FRAGMENT="""
        #version 450
        layout(location=0) in vec2 shadowUV;layout(location=1) in float shadowAlpha;layout(location=2) flat in uint shadowFlags;
        uniform sampler2D ShadowAtlas;
        void main(){const float cut[4]=float[4](0.0,.1,.5,1.0);if(textureLod(ShadowAtlas,shadowUV,0).a*shadowAlpha<cut[(shadowFlags>>1u)&3u])discard;}
        """;
}
