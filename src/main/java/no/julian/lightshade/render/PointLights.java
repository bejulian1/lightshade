package no.julian.lightshade.render;

import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Light-emitting blocks near the camera, for per-pixel point lights in the terrain shader. Rescanned a
 * few times a second; chunk sections whose palette holds no light source are skipped without reading
 * their blocks. Each light is (x, y, z relative to the camera, level + 16 * colour class). */
final class PointLights {
    static final int MAX=32,RADIUS=28;
    /** 0 fire (torches, lanterns, lava), 1 soul fire, 2 redstone, 3 cool white (sea lantern, end rod, froglights). */
    private static final Map<BlockState,Integer> CLASSES=new IdentityHashMap<>();
    private record Light(double x,double y,double z,int level,int type,double distance){}
    private List<Light> lights=List.of();
    private long lastScan;
    private double scanX=Double.NaN,scanY,scanZ;
    private float nearRadius=RADIUS-4;

    void update(Vec3 camera){
        var mc=Minecraft.getInstance();if(mc.level==null){lights=List.of();return;}
        long now=System.nanoTime();
        boolean moved=Double.isNaN(scanX)||Math.abs(camera.x-scanX)+Math.abs(camera.y-scanY)+Math.abs(camera.z-scanZ)>3;
        if(!moved&&now-lastScan<250_000_000L)return;
        lastScan=now;scanX=camera.x;scanY=camera.y;scanZ=camera.z;
        var level=mc.level;List<Light> found=new ArrayList<>();
        int cx0=((int)Math.floor(camera.x)-RADIUS)>>4,cx1=((int)Math.floor(camera.x)+RADIUS)>>4;
        int cz0=((int)Math.floor(camera.z)-RADIUS)>>4,cz1=((int)Math.floor(camera.z)+RADIUS)>>4;
        int minSection=level.getMinSectionY(),count=level.getSectionsCount();
        int sy0=Math.max(minSection,((int)Math.floor(camera.y)-RADIUS)>>4),sy1=Math.min(minSection+count-1,((int)Math.floor(camera.y)+RADIUS)>>4);
        for(int cx=cx0;cx<=cx1;cx++)for(int cz=cz0;cz<=cz1;cz++){
            LevelChunk chunk=level.getChunkSource().getChunk(cx,cz,ChunkStatus.FULL,false);if(chunk==null)continue;
            for(int sy=sy0;sy<=sy1;sy++){
                LevelChunkSection section=chunk.getSection(sy-minSection);
                if(section.hasOnlyAir()||!section.maybeHas(state->state.getLightEmission()>0))continue;
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    BlockState state=section.getBlockState(x,y,z);int emission=state.getLightEmission();
                    if(emission<=0)continue;
                    double px=(cx<<4)+x+.5,py=(sy<<4)+y+.55,pz=(cz<<4)+z+.5;
                    double d=Math.sqrt((px-camera.x)*(px-camera.x)+(py-camera.y)*(py-camera.y)+(pz-camera.z)*(pz-camera.z));
                    if(d<=RADIUS)found.add(new Light(px,py,pz,emission,type(state),d));
                }
            }
        }
        found.sort(Comparator.comparingDouble(Light::distance));
        lights=found.size()>MAX?List.copyOf(found.subList(0,MAX)):List.copyOf(found);
        // With more sources than slots, only trust point lights well inside the farthest one kept;
        // beyond that the shader falls back to Minecraft's own light levels.
        nearRadius=found.size()>MAX?(float)Math.max(6,lights.getLast().distance()-4):RADIUS-4;
    }
    /** Writes 16 bytes of count/near radius and MAX lights of 16 bytes. */
    void write(ByteBuffer bytes,Vec3 camera){
        bytes.putFloat(lights.size()).putFloat(nearRadius).putFloat(0).putFloat(0);
        for(int i=0;i<MAX;i++){
            if(i<lights.size()){Light l=lights.get(i);bytes.putFloat((float)(l.x-camera.x)).putFloat((float)(l.y-camera.y)).putFloat((float)(l.z-camera.z)).putFloat(l.level+16*l.type);}
            else bytes.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        }
    }
    int count(){return lights.size();}
    private static int type(BlockState state){
        return CLASSES.computeIfAbsent(state,s->{
            String id=BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            if(id.contains("soul"))return 1;
            if(id.contains("redstone"))return 2;
            if(id.contains("sea_lantern")||id.contains("end_rod")||id.contains("froglight")||id.contains("beacon")||id.contains("conduit")||id.contains("sea_pickle")||id.contains("amethyst")||id.contains("glow_lichen"))return 3;
            return 0;
        });
    }
}
