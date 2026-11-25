package no.julian.lightshade.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Original renderer: native terrain program replacement and an explicit GPU graph. */
public final class LightshadeRenderer {
    static final BindGroupLayout FRAME_LAYOUT=BindGroupLayout.builder().withUniform("LsFrame",UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout OPAQUE_LAYOUT=BindGroupLayout.builder().withUniform("LsOpaque",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("LsOpaqueDepth",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("LsMaterials",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("LsNoise",UniformType.COMBINED_IMAGE_SAMPLER).build();
    // 768 bytes of frame data, then the point-light count and PointLights.MAX lights (frame.glsl).
    private static final int FRAME_BYTES=768+16+PointLights.MAX*16;
    private static final Matrix4f projection=new Matrix4f(),view=new Matrix4f();
    private static final Set<RenderPipeline> observed=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<CompiledRenderPipeline,RenderPipeline> nativeDescriptors=new IdentityHashMap<>();
    private static final AtomicLong requests=new AtomicLong();
    private static final DeferredGpuCleanup retired=new DeferredGpuCleanup();
    private static final System.Logger LOGGER=System.getLogger(LightshadeRenderer.class.getName());
    private static Path packDirectory;
    private static volatile State active;
    private static volatile String failure="",lastLoadFailure="";
    private static long generation,frames,terrainDraws,opaqueCaptures;
    private static final long started=System.nanoTime();
    private static GpuBufferSlice frameUniform;
    private static Map<String,Object> deviceInfo=Map.of();
    private static float eyeSky=1,eyeBlock=0,surfaceWet=0;private static long eyeTime;
    private static final GpuProfile profile=new GpuProfile();
    private static final PointLights lights=new PointLights();

    private LightshadeRenderer(){}
    public static void configure(Path directory){packDirectory=directory.toAbsolutePath().normalize();}
    public static RenderPack activePack(){State state=active;return state==null?null:state.pack;}
    public static boolean enabled(){return active!=null;}
    public static boolean ownsSky(){State state=active;var level=Minecraft.getInstance().level;return state!=null&&state.ownsSky&&level!=null&&level.dimension().equals(Level.OVERWORLD);}
    public static boolean ownsRain(){State state=active;return state!=null&&state.ownsRain;}
    public static String statusText(){State state=active;return state==null?(failure.isBlank()?"Lightshade disabled":"Lightshade disabled: "+failure):state.pack.name()+" — "+state.terrain.size()+" terrain pipelines, "+state.passes.size()+" graph passes";}
    public static Map<String,Object> status(){
        State state=active;Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",state!=null);result.put("pack",state==null?"":state.pack.id());result.put("name",state==null?"":state.pack.name());
        result.put("sha256",state==null?"":state.pack.sha256());result.put("options",state==null?Map.of():state.pack.options());
        result.put("generation",generation);result.put("frames",frames);result.put("terrainDraws",terrainDraws);result.put("opaqueCaptures",opaqueCaptures);
        result.put("terrainPipelineCount",state==null?0:state.terrain.size());result.put("passes",state==null?0:state.passes.size());
        result.put("failure",failure);result.put("lastLoadFailure",lastLoadFailure);result.put("device",deviceInfo);
        result.put("pendingCleanup",retired.size());result.put("pointLights",lights.count());result.put("gpuProfileMs",profile.status());
        result.put("standalone",true);result.put("dynamicShadowMaps",state!=null&&state.shadows!=null);result.put("shadow",state==null?Map.of():state.shadows.status());result.put("materials",state==null||state.materials==null?Map.of():state.materials.status());result.put("nativeTerrainReplacement",true);
        result.put("platformVerification","Ubuntu RTX2080 target; other Vulkan hardware and Apple Silicon unverified");
        return Collections.unmodifiableMap(result);
    }
    public static CompletableFuture<Void> apply(String id,Map<String,Double> options){
        long request=requests.incrementAndGet();CompletableFuture<Void> result=new CompletableFuture<>();
        Minecraft mc=Minecraft.getInstance();mc.execute(()->{
            State candidate=null;
            try{
                if(packDirectory==null)throw new IllegalStateException("Lightshade pack directory not configured");
                RenderSystem.assertOnRenderThread();checkDevice();
                RenderPack pack=RenderPack.load(packDirectory,id,Map.copyOf(options));
                candidate=new State(pack,++generation);
                candidate.compileGraph();
                candidate.ensureTargets(mc.gameRenderer.mainRenderTarget());
                for(RenderPipeline original:observed)candidate.terrain(original);
                if(request!=requests.get()){candidate.close();result.completeExceptionally(new IllegalStateException("Superseded Lightshade load"));return;}
                State old=active;active=candidate;failure="";lastLoadFailure="";frameUniform=null;
                if(old!=null)RenderSystem.queueFencedTask(old::close);
                result.complete(null);
            }catch(Throwable error){
                if(candidate!=null)candidate.close();lastLoadFailure=shortError(error);
                if(active==null)failure=lastLoadFailure;result.completeExceptionally(error);
            }
        });return result;
    }
    public static CompletableFuture<Void> reload(){State state=active;return state==null?CompletableFuture.completedFuture(null):apply(state.pack.id(),state.pack.options());}
    /** Called only after a successful native resource reload has completed. */
    public static CompletableFuture<Void> resourceReloaded(){
        CompletableFuture<Void> result=new CompletableFuture<>();
        Minecraft.getInstance().execute(()->{
            State candidate=null;
            try{
                RenderSystem.assertOnRenderThread();
                State previous=active;active=null;invalidate();
                if(previous==null){result.complete(null);return;}
                RenderSystem.queueFencedTask(previous::close);
                // A resource event must not cancel a user's already queued apply.
                // That apply will execute afterward against the fresh resources.
                long request=requests.get();
                RenderPack pack=RenderPack.load(packDirectory,previous.pack.id(),previous.pack.options());
                candidate=new State(pack,++generation);candidate.compileGraph();
                candidate.ensureTargets(Minecraft.getInstance().gameRenderer.mainRenderTarget());
                if(request!=requests.get()){
                    candidate.close();result.complete(null);return;
                }
                active=candidate;failure="";lastLoadFailure="";result.complete(null);
            }catch(Throwable error){
                if(candidate!=null)candidate.close();
                failure=shortError(error);lastLoadFailure=failure;result.completeExceptionally(error);
            }
        });
        return result;
    }
    public static void disable(){requests.incrementAndGet();Minecraft.getInstance().execute(()->{State old=active;active=null;frameUniform=null;if(old!=null)RenderSystem.queueFencedTask(old::close);});}
    public static void close(){profile.close();State old=active;active=null;frameUniform=null;if(old!=null)old.close();retired.closeNow();observed.clear();nativeDescriptors.clear();}

    /** Hook at RenderSystem.getCompiledPipeline, returning null for every nonterrain program. */
    public static CompiledRenderPipeline replacement(RenderPipeline original){
        if(!isTerrain(original))return null;
        observed.add(original);State state=active;if(state==null)return null;
        try{return state.terrain(original);}catch(Throwable error){failClosed(error);return null;}
    }
    public static void observeCompiled(RenderPipeline original,CompiledRenderPipeline compiled){if(compiled!=null)nativeDescriptors.put(compiled,original);}
    static RenderPipeline nativeDescriptor(CompiledRenderPipeline compiled){return nativeDescriptors.get(compiled);}
    public static void renderFeatureShadows(net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame prepared){State state=active;if(state==null)return;try{state.shadows.renderFeatures(prepared,view);}catch(Throwable error){failClosed(error);}}
    public static boolean castsShadowOutOfView(net.minecraft.world.entity.Entity entity,double x,double y,double z){State state=active;return state!=null&&state.shadows.casts()&&entity.distanceToSqr(x,y,z)<ShadowMaps.OFFSCREEN_CASTERS*ShadowMaps.OFFSCREEN_CASTERS;}
    public static void observe(RenderPipeline original){if(isTerrain(original))observed.add(original);}
    public static boolean isTerrain(RenderPipeline pipeline){
        return pipeline.getShaders().values().stream().anyMatch(id->id.getNamespace().equals("sodium")&&id.getPath().contains("block_layer_opaque"));
    }
    /** Call just after a terrain render pass receives the replacement compiled pipeline. */
    public static void bindTerrain(RenderPass pass){
        State state=active;if(state==null)return;
        try{
            if(frameUniform==null||state.opaque==null)throw new IllegalStateException("Terrain binding before Lightshade frame preparation");
            if(frameUniform.buffer().isClosed())throw new IllegalStateException("Lightshade frame expired before terrain rendering");
            pass.setUniform("LsFrame",frameUniform);
            var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("LsOpaque",state.opaque.getColorTextureView(),sampler);
            pass.setUniform("LsOpaqueDepth",state.opaque.getDepthTextureView(),sampler);
            pass.setUniform("LsMaterials",state.materials.view(),sampler);
            pass.setUniform("LsNoise",state.noise.view(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            state.shadows.bind(pass);
            terrainDraws++;
        }catch(Throwable error){failClosed(error);}
    }
    /** Matrices must be the world scene matrices, before hand/GUI projection changes. */
    public static void updateFrame(Matrix4fc projectionMatrix,Matrix4fc viewMatrix){
        retired.fence(RenderSystem::queueFencedTask);
        projection.set(projectionMatrix);view.set(viewMatrix);State state=active;
        if(state!=null){state.opaqueReady=false;try{profile.begin();var mc=Minecraft.getInstance();state.ensureTargets(mc.gameRenderer.mainRenderTarget());
            // Built on the first world frame: at game start the block atlas and models may not be loaded yet.
            if(state.materials==null)state.materials=new MaterialMap();
            if(state.noise==null)state.noise=new NoiseVolume();
            var camera=mc.gameRenderer.mainCamera().position();float angle=mc.level==null?0:mc.level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE,camera);lights.update(camera);
            // The frame block goes first: a pack's shadow program uses its time and camera to wave plants.
            uploadFrame();state.shadows.render(camera,angle,frameUniform,state.materials.view());profile.mark("shadow map");}catch(Throwable error){failClosed(error);}}
    }
    /** Snapshot color+depth after opaque geometry, before translucent terrain starts. */
    public static void captureOpaque(RenderTarget target){
        State state=active;if(state==null)return;
        try{profile.mark("world opaque");state.ensureTargets(target);state.opaque.copyColorFrom(target);state.opaque.copyDepthFrom(target);state.opaqueReady=true;opaqueCaptures++;profile.mark("opaque snapshot");uploadFrame();}
        catch(Throwable error){failClosed(error);}
    }
    /** Execute once after the world scene, before first-person hand and GUI. */
    public static void composite(RenderTarget target){
        // LevelRenderer.render has returned: opaque, classic/OIT and native
        // resolve passes are closed. This must run even after failClosed().
        retired.fence(RenderSystem::queueFencedTask);
        State state=active;if(state==null)return;
        try{
            state.ensureTargets(target);if(frameUniform==null)uploadFrame();
            if(frameUniform.buffer().isClosed())throw new IllegalStateException("Lightshade frame expired before composition");
            profile.mark("world translucent");
            state.scene.copyColorFrom(target);state.scene.copyDepthFrom(target);
            profile.mark("scene snapshot");
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            // Pass outputs are often smaller than the screen: read them filtered. Snapshots and depth stay exact.
            var filtered=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            for(CompiledPass compiled:state.passes){
                RenderPack.Pass pass=compiled.definition;GpuTextureView output=pass.output().equals("$screen")?target.getColorTextureView():state.targets.get(pass.output()).getColorTextureView();
                try(RenderPass drawing=encoder.createRenderPass(()->"Lightshade "+pass.name(),output,Optional.empty())){
                    drawing.setPipeline(compiled.pipeline);drawing.setUniform("LsFrame",frameUniform);state.shadows.bind(drawing);
                    for(var input:pass.inputs().entrySet())drawing.setUniform(input.getKey(),state.input(input.getValue()),input.getValue().startsWith("$")&&!input.getValue().equals("$noise")?sampler:filtered);
                    drawing.draw(3,1,0,0);
                }
                profile.mark("pass "+pass.name());
            }
            // Hand and HUD rendering still use native transient allocations.
            // Let Minecraft submit the shared encoder at its frame boundary.
            frames++;
        }catch(Throwable error){failClosed(error);}
        finally{retired.fence(RenderSystem::queueFencedTask);}
    }
    public static void invalidate(){observed.clear();nativeDescriptors.clear();frameUniform=null;State state=active;if(state!=null)state.opaqueReady=false;}

    private static void uploadFrame(){
        State state=active;if(state==null)return;
        Minecraft mc=Minecraft.getInstance();var target=mc.gameRenderer.mainRenderTarget();
        ByteBuffer bytes=ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.nativeOrder());
        projection.get(0,bytes);view.get(64,bytes);new Matrix4f(projection).invert().get(128,bytes);new Matrix4f(view).invert().get(192,bytes);
        float time=(System.nanoTime()-started)*1e-9f,clock=0,rain=0,dimension=2,angle=0;
        var camera=mc.gameRenderer.mainCamera().position();
        if(mc.level!=null){clock=(float)Math.floorMod(mc.level.getOverworldClockTime(),24000L);rain=mc.level.getRainLevel(1);
            dimension=mc.level.dimension().equals(Level.OVERWORLD)?0:mc.level.dimension().equals(Level.NETHER)?-1:mc.level.dimension().equals(Level.END)?1:2;
            angle=mc.level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE,camera);
        }
        bytes.position(256);put(bytes,time,clock,rain,dimension);
        org.joml.Vector3f sun=ShadowMaps.celestial(angle,state.shadows.sunTilt());
        put(bytes,sun.x,sun.y,sun.z,1);
        put(bytes,(float)camera.x,(float)camera.y,(float)camera.z,1);
        updateSurfaceWet(mc,camera);put(bytes,target.width,target.height,state.opaqueReady?1:0,surfaceWet);
        var atlas=mc.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        for(String id:List.of("water_still","water_flow","water_overlay"))sprite(bytes,atlas,id);
        for(String id:List.of("lava_still","lava_flow","glowstone","sea_lantern","shroomlight","ochre_froglight_side","verdant_froglight_side","pearlescent_froglight_side","redstone_lamp_on","jack_o_lantern","magma","fire_0","fire_1","soul_fire_0","soul_fire_1","end_rod"))sprite(bytes,atlas,id);
        var fog=mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
        put(bytes,fog.color.x,fog.color.y,fog.color.z,fog.environmentalStart);
        put(bytes,fog.environmentalEnd,fog.renderDistanceStart,fog.renderDistanceEnd,fog.skyEnd);
        org.joml.Vector4f[] held=new org.joml.Vector4f[4];
        heldLight(held,0,mc.player==null?net.minecraft.world.item.ItemStack.EMPTY:mc.player.getMainHandItem(),camera,mc.player!=null&&mc.player.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT?-1:1);
        heldLight(held,1,mc.player==null?net.minecraft.world.item.ItemStack.EMPTY:mc.player.getOffhandItem(),camera,mc.player!=null&&mc.player.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT?1:-1);
        for(var value:held)put(bytes,value.x,value.y,value.z,value.w);
        var medium=mc.gameRenderer.mainCamera().getFluidInCamera();float fogType=medium==net.minecraft.world.level.material.FogType.WATER?1:medium==net.minecraft.world.level.material.FogType.LAVA?2:medium==net.minecraft.world.level.material.FogType.POWDER_SNOW?3:0;
        float moonAngle=angle+180,phase=0;org.joml.Vector3fc nativeSky=new org.joml.Vector3f();float stars=0;
        if(mc.level!=null){moonAngle=mc.level.environmentAttributes().getValue(EnvironmentAttributes.MOON_ANGLE,camera);phase=mc.level.environmentAttributes().getValue(EnvironmentAttributes.MOON_PHASE,camera).index();nativeSky=mc.level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR,camera);stars=mc.level.environmentAttributes().getValue(EnvironmentAttributes.STAR_BRIGHTNESS,camera);}
        // Eye light, eased over about a second: lets haze and godrays fade in caves and houses.
        if(mc.level!=null&&mc.player!=null){var eyePos=net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
            float sky=mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,eyePos)/15f,block=mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,eyePos)/15f;
            long now=System.nanoTime();float blend=eyeTime==0?1:1-(float)Math.exp(-(now-eyeTime)*1e-9*1.5);eyeTime=now;
            eyeSky+=(sky-eyeSky)*blend;eyeBlock+=(block-eyeBlock)*blend;}
        put(bytes,fogType,phase,eyeSky,eyeBlock);put(bytes,nativeSky.x(),nativeSky.y(),nativeSky.z(),stars);
        org.joml.Vector3f moon=ShadowMaps.celestial(moonAngle,state.shadows.sunTilt());put(bytes,moon.x,moon.y,moon.z,phase);
        lights.write(bytes,camera);
        bytes.flip();
        var device=RenderSystem.getDevice();int alignment=Math.max(16,device.getDeviceInfo().limits().minUniformOffsetAlignment());
        frameUniform=device.createCommandEncoder().transientMemory().uploadGpu(bytes,alignment,GpuBuffer.USAGE_UNIFORM);
    }
    /** How wet the land around the camera is: rises while rain falls here (not in deserts or snow), and dries slowly after. */
    private static long wetTime;
    private static void updateSurfaceWet(Minecraft mc,net.minecraft.world.phys.Vec3 camera){
        long now=System.nanoTime();float seconds=wetTime==0?0:(now-wetTime)*1e-9f;wetTime=now;
        float target=0;
        if(mc.level!=null&&mc.level.getRainLevel(1)>0){var pos=net.minecraft.core.BlockPos.containing(camera);
            if(mc.level.getBiome(pos).value().getPrecipitationAt(pos,mc.level.getSeaLevel())==net.minecraft.world.level.biome.Biome.Precipitation.RAIN)target=mc.level.getRainLevel(1);}
        surfaceWet+=(target-surfaceWet)*(1-(float)Math.exp(-seconds/(target>surfaceWet?5f:45f)));
    }
    private static void heldLight(org.joml.Vector4f[] result,int hand,net.minecraft.world.item.ItemStack stack,net.minecraft.world.phys.Vec3 camera,int side){
        var mc=Minecraft.getInstance();String id=stack.isEmpty()?"":net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int emission=stack.getItem() instanceof net.minecraft.world.item.BlockItem block?block.getBlock().defaultBlockState().getLightEmission():0;
        if(id.equals("lava_bucket"))emission=15;else if(id.equals("blaze_rod")||id.equals("blaze_powder"))emission=10;else if(id.equals("glow_berries")||id.equals("glow_ink_sac"))emission=8;
        var offset=new org.joml.Vector3f(side*.32f,-.28f,-.35f);new Matrix4f(view).invert().transformDirection(offset);
        if(mc.player!=null){var eye=mc.player.getEyePosition().subtract(camera);offset.add((float)eye.x,(float)eye.y,(float)eye.z);}
        result[hand]=new org.joml.Vector4f(offset,emission);
        var color=id.contains("soul")?new org.joml.Vector3f(.28f,.75f,1.05f):id.contains("redstone")?new org.joml.Vector3f(1.1f,.12f,.04f):id.contains("sea_lantern")?new org.joml.Vector3f(.65f,.95f,1.05f):new org.joml.Vector3f(1.12f,.69f,.31f);
        result[2+hand]=new org.joml.Vector4f(color.mul(emission/15f),1);
    }
    private static void sprite(ByteBuffer bytes,TextureAtlas atlas,String name){var sprite=atlas.getSprite(Identifier.withDefaultNamespace("block/"+name));if(sprite==atlas.missingSprite())put(bytes,0,0,0,0);else put(bytes,sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1());}
    private static void put(ByteBuffer buffer,float a,float b,float c,float d){buffer.putFloat(a).putFloat(b).putFloat(c).putFloat(d);}
    private static void checkDevice(){
        var info=RenderSystem.getDevice().getDeviceInfo();Map<String,Object> details=new LinkedHashMap<>();
        details.put("name",info.name());details.put("vendor",info.vendorName());details.put("driver",info.driverInfo());details.put("backend",info.backendName());
        details.put("zZeroToOne",info.isZZeroToOne());details.put("maxTextureSize",info.limits().maxTextureSize());details.put("maxMemoryAllocationSize",info.limits().maxMemoryAllocationSize());
        details.put("maxColorAttachments",info.limits().maxColorAttachments());details.put("uniformAlignment",info.limits().minUniformOffsetAlignment());details.put("operatingSystem",System.getProperty("os.name"));deviceInfo=Map.copyOf(details);
        if(!info.backendName().toLowerCase(Locale.ROOT).contains("vulkan")||!info.isZZeroToOne())throw new IllegalStateException("Lightshade currently requires native Vulkan with zero-to-one clip depth; native renderer retained");
        if(info.limits().maxColorAttachments()<1||info.limits().maxTextureSize()<1024)throw new IllegalStateException("GPU reports insufficient baseline render limits; native renderer retained");
    }
    private static void failClosed(Throwable error){
        failure=shortError(error);State old=active;active=null;frameUniform=null;
        // Pipeline lookup and uniform binding can fail inside a live native
        // render pass. Even creating a fence here is illegal; retain all GPU
        // objects until composite() reaches the pass-free world boundary.
        if(old!=null){retired.defer(old::close);LOGGER.log(System.Logger.Level.ERROR,"Lightshade disabled after rendering failure: "+failure,error);}
    }
    private static String shortError(Throwable error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();return cause.getClass().getSimpleName()+": "+Objects.toString(cause.getMessage(),"");}

    private record CompiledPass(RenderPack.Pass definition,CompiledRenderPipeline pipeline){}
    private static final class State implements AutoCloseable {
        final RenderPack pack;final long generation;final String shadowVertex;final PackShaderSource sources;final boolean ownsSky,ownsRain;
        final List<CompiledPass> passes=new ArrayList<>();final Map<RenderPipeline,CompiledRenderPipeline> terrain=new IdentityHashMap<>();
        final Map<String,TextureTarget> targets=new LinkedHashMap<>();
        TextureTarget scene,opaque;int width,height;boolean opaqueReady,closed;
        ShadowMaps shadows;
        MaterialMap materials;
        NoiseVolume noise;
        State(RenderPack pack,long generation){this.pack=pack;this.generation=generation;sources=new PackShaderSource(pack);var manifest=com.google.gson.JsonParser.parseString(pack.files().get("lightshade.json")).getAsJsonObject();ownsSky=manifest.has("ownsSky")&&manifest.get("ownsSky").getAsBoolean();ownsRain=manifest.has("ownsRain")&&manifest.get("ownsRain").getAsBoolean();
            shadowVertex=manifest.has("shadow")?manifest.getAsJsonObject("shadow").get("vertex").getAsString():null;}
        void compileGraph(){
            Identifier vertex=id("fullscreen");sources.put(vertex,BuiltinShaders.FULLSCREEN);
            for(RenderPack.Pass pass:pack.passes()){
                Identifier fragment=id("pass_"+pass.name());sources.path(fragment,pass.fragment());
                var layout=BindGroupLayout.builder();pass.inputs().keySet().forEach(name->layout.withUniform(name,UniformType.COMBINED_IMAGE_SAMPLER));
                var builder=RenderPipeline.builder().withLocation(id("graph_"+pass.name())).withVertexShader(vertex).withFragmentShader(fragment)
                    .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(new ColorTargetState(Optional.empty(),pass.format(),ColorTargetState.WRITE_ALL))
                    .withBindGroupLayout(FRAME_LAYOUT).withBindGroupLayout(layout.build()).withBindGroupLayout(ShadowMaps.RECEIVER_LAYOUT);
                defines(builder);passes.add(new CompiledPass(pass,compile(builder.build())));
            }
            shadows=new ShadowMaps(sources,generation,pack.options(),pack.budgetBytes(),shadowVertex==null?null:pack.files().get(shadowVertex));
            if(shadowVertex!=null&&!pack.files().containsKey(shadowVertex))throw new IllegalStateException("Missing shadow program "+shadowVertex);
        }
        CompiledRenderPipeline terrain(RenderPipeline original){
            var existing=terrain.get(original);if(existing!=null)return existing;
            Identifier vertex=id("terrain_vertex"),fragment=id("terrain_fragment");sources.path(vertex,pack.vertex());sources.path(fragment,pack.fragment());
            var builder=RenderPipeline.builder().withLocation(id("terrain_"+terrain.size())).withVertexShader(vertex).withFragmentShader(fragment)
                .withCull(original.isCull()).withPolygonMode(original.getPolygonMode()).withPrimitiveTopology(original.getPrimitiveTopology()).withPushConstantSize(original.pushConstantSize());
            if(original.getDepthStencilState()!=null)builder.withDepthStencilState(original.getDepthStencilState());
            for(int i=0;i<original.getColorTargetStates().size();i++){
                ColorTargetState color=original.getColorTargetStates().get(i);if(color==null)builder.withUnusedColorTargetState(i);else builder.withColorTargetState(i,color);
            }
            for(int i=0;i<original.getVertexFormatBindings().size();i++)if(original.getVertexFormatBindings().get(i)!=null)builder.withVertexBinding(i,original.getVertexFormatBindings().get(i));
            original.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
            builder.withBindGroupLayout(FRAME_LAYOUT).withBindGroupLayout(OPAQUE_LAYOUT).withBindGroupLayout(ShadowMaps.RECEIVER_LAYOUT);
            original.getShaderDefines().flags().forEach(builder::withShaderDefine);
            original.getShaderDefines().values().forEach((key,value)->{if(value.matches("-?[0-9]+"))builder.withShaderDefine(key,Integer.parseInt(value));else builder.withShaderDefine(key,Float.parseFloat(value));});
            defines(builder);CompiledRenderPipeline compiled=compile(builder.build());terrain.put(original,compiled);return compiled;
        }
        void defines(RenderPipeline.Builder builder){pack.options().forEach((key,value)->{if(value==Math.rint(value))builder.withShaderDefine(key,value.intValue());else builder.withShaderDefine(key,value.floatValue());});}
        Identifier id(String name){return Identifier.fromNamespaceAndPath("lightshade","runtime/g"+generation+"/"+name);}
        CompiledRenderPipeline compile(RenderPipeline pipeline){
            var pending=RenderSystem.getDevice().compilePipeline(pipeline,sources,Runnable::run).join();
            CompiledRenderPipeline compiled=pending.finishCompile();if(compiled==null)throw new IllegalStateException("GPU rejected "+pipeline.getLocation());return compiled;
        }
        void ensureTargets(RenderTarget reference){
            if(width==reference.width&&height==reference.height&&scene!=null)return;
            var limits=RenderSystem.getDevice().getDeviceInfo().limits();int w=reference.width,h=reference.height;
            if(w<1||h<1||w>limits.maxTextureSize()||h>limits.maxTextureSize())throw new IllegalStateException("Render size exceeds GPU limits");
            long allocation=(long)w*h*16+(shadows==null?0:shadows.memoryBytes());
            for(RenderPack.Pass pass:pack.passes())if(!pass.output().equals("$screen"))allocation+=(long)Math.max(1,(int)(w*pass.scale()))*Math.max(1,(int)(h*pass.scale()))*pass.format().blockSize();
            if(allocation>pack.budgetBytes())throw new IllegalStateException("Lightshade graph exceeds declared memory budget: "+allocation+" bytes");
            if(limits.maxMemoryAllocationSize()>0&&(long)w*h*8>limits.maxMemoryAllocationSize())throw new IllegalStateException("A graph target exceeds device allocation limit");
            List<TextureTarget> created=new ArrayList<>();Map<String,TextureTarget> replacements=new LinkedHashMap<>();TextureTarget nextScene,nextOpaque;
            try{
                nextScene=new TextureTarget("Lightshade scene snapshot",w,h,reference.getColorTexture().getFormat(),reference.getDepthTexture().getFormat());created.add(nextScene);
                nextOpaque=new TextureTarget("Lightshade opaque snapshot",w,h,reference.getColorTexture().getFormat(),reference.getDepthTexture().getFormat());created.add(nextOpaque);
                var encoder=RenderSystem.getDevice().createCommandEncoder();encoder.clearColorAndDepthTextures(nextOpaque.getColorTexture(),new Vector4f(),nextOpaque.getDepthTexture(),0);
                for(RenderPack.Pass pass:pack.passes())if(!pass.output().equals("$screen")){var target=new TextureTarget("Lightshade "+pass.output(),Math.max(1,(int)(w*pass.scale())),Math.max(1,(int)(h*pass.scale())),pass.format(),null);created.add(target);replacements.put(pass.output(),target);}
            }catch(Throwable error){created.forEach(TextureTarget::destroyBuffers);throw error;}
            List<TextureTarget> prior=new ArrayList<>(targets.values());if(scene!=null)prior.add(scene);if(opaque!=null)prior.add(opaque);targets.clear();targets.putAll(replacements);scene=nextScene;opaque=nextOpaque;
            width=w;height=h;opaqueReady=false;if(!prior.isEmpty())RenderSystem.queueFencedTask(()->prior.forEach(TextureTarget::destroyBuffers));
        }
        GpuTextureView input(String name){return switch(name){case "$scene"->scene.getColorTextureView();case "$depth"->scene.getDepthTextureView();case "$opaque"->opaque.getColorTextureView();case "$opaqueDepth"->opaque.getDepthTextureView();case "$shadow"->shadows.textureView();case "$noise"->noise.view();default->targets.get(name).getColorTextureView();};}
        @Override public void close(){if(closed)return;closed=true;terrain.values().forEach(CompiledRenderPipeline::close);passes.forEach(pass->pass.pipeline.close());if(shadows!=null)shadows.close();if(materials!=null)materials.close();if(noise!=null)noise.close();sources.close();targets.values().forEach(TextureTarget::destroyBuffers);if(scene!=null)scene.destroyBuffers();if(opaque!=null)opaque.destroyBuffers();}
    }
}
