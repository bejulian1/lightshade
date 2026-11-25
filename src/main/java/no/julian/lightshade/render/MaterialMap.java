package no.julian.lightshade.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.joml.Vector3f;
import java.lang.reflect.Field;
import java.util.*;

/** Per-sprite material data for the terrain shaders, read from the baked block models so that modded
 * plants and lamps are covered too. One RGBA8 texel per atlas cell: kind, row in sprite, sprite rows,
 * light emission. Kinds: 1 plant, 2 leaves, 3 upper half of a tall plant, 4 hanging plant. */
final class MaterialMap implements AutoCloseable {
    static final int PLANT=1,LEAVES=2,UPPER=3,HANGING=4;
    private static final Field SPRITES;
    static{try{SPRITES=TextureAtlas.class.getDeclaredField("sprites");SPRITES.setAccessible(true);}catch(ReflectiveOperationException error){throw new ExceptionInInitializerError(error);}}
    private final GpuTexture texture;
    private final GpuTextureView view;
    /** The backend reads the pixels when the frame is submitted, not at writeToTexture: keep them until close. */
    private final NativeImage image;
    private final int cell,waving,glowing;
    private final String probe;

    MaterialMap(){
        var mc=Minecraft.getInstance();
        TextureAtlas atlas=mc.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        int atlasWidth=atlas.getTexture().getWidth(0),atlasHeight=atlas.getTexture().getHeight(0);
        Map<TextureAtlasSprite,Integer> kinds=new IdentityHashMap<>(),emission=new IdentityHashMap<>();
        var models=mc.getModelManager().getBlockStateModelSet();
        List<BlockStateModelPart> parts=new ArrayList<>();
        Vector3f a=new Vector3f(),b=new Vector3f();
        for(Block block:BuiltInRegistries.BLOCK)for(BlockState state:block.getStateDefinition().getPossibleStates()){
            int kind=kind(block,state),light=state.getLightEmission();
            if(kind==0&&light==0)continue;
            parts.clear();models.get(state).collectParts(RandomSource.create(42L),parts);
            for(BlockStateModelPart part:parts)for(int face=0;face<=6;face++){
                for(BakedQuad quad:part.getQuads(face==6?null:Direction.from3DDataValue(face))){
                    TextureAtlasSprite sprite=quad.materialInfo().sprite();
                    if(sprite==null||atlas.getSprite(sprite.contents().name())!=sprite)continue; // only sprites of this atlas
                    int glow=Math.max(light,quad.materialInfo().lightEmission());
                    if(glow>0)emission.merge(sprite,glow,Math::max);
                    if(kind==0)continue;
                    // Plants sway only on their upright quads; flat petals and pads stay put.
                    a.set(quad.position1()).sub(quad.position0());b.set(quad.position2()).sub(quad.position0());a.cross(b);
                    if(kind!=LEAVES&&Math.abs(a.y)>0.7f*a.length())continue;
                    kinds.merge(sprite,kind,Math::max);
                }
            }
        }
        Set<TextureAtlasSprite> marked=Collections.newSetFromMap(new IdentityHashMap<>());marked.addAll(kinds.keySet());marked.addAll(emission.keySet());
        int step=0;
        // Sprites are padded in the 26.3 atlas: getX()/getY() is the padded slot, the UVs are the pixels the
        // shader actually samples. Cells follow the UVs.
        for(TextureAtlasSprite sprite:sprites(atlas)){int[] r=rect(sprite,atlasWidth,atlasHeight);step=gcd(gcd(gcd(gcd(step,r[0]),r[1]),r[2]),r[3]);}
        step=Math.max(1,step);
        while((long)(atlasWidth/step)*(atlasHeight/step)>(1L<<22))step*=2;
        cell=step;
        int width=Math.max(1,(atlasWidth+step-1)/step),height=Math.max(1,(atlasHeight+step-1)/step);
        image=new NativeImage(width,height,true);
        {
            for(TextureAtlasSprite sprite:marked){
                int kind=kinds.getOrDefault(sprite,0),glow=emission.getOrDefault(sprite,0)*17;
                int[] r=rect(sprite,atlasWidth,atlasHeight);
                int x0=r[0]/step,y0=r[1]/step;
                int columns=Math.max(1,(r[2]+step-1)/step),rows=Math.min(255,Math.max(1,(r[3]+step-1)/step));
                for(int row=0;row<rows;row++)for(int column=0;column<columns;column++){
                    int x=x0+column,y=y0+row;
                    if(x<width&&y<height)image.setPixel(x,y,(glow<<24)|(kind<<16)|(row<<8)|rows);
                }
            }
            var device=RenderSystem.getDevice();
            texture=device.createTexture("Lightshade materials",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,width,height,1,1);
            device.createCommandEncoder().writeToTexture(texture,image);
            view=device.createTextureView(texture);
        }
        waving=kinds.size();glowing=emission.size();
        // A readable check of the written data: the short grass sprite's first cell.
        TextureAtlasSprite grass=atlas.getSprite(net.minecraft.resources.Identifier.withDefaultNamespace("block/short_grass"));
        probe="short_grass@"+grass.getX()+","+grass.getY()+" "+grass.contents().width()+"x"+grass.contents().height()+" u="+grass.getU0()+" v="+grass.getV0()
            +" kind="+kinds.getOrDefault(grass,0)+" texel=#"+Integer.toHexString(image.getPixel(rect(grass,atlasWidth,atlasHeight)[0]/step,rect(grass,atlasWidth,atlasHeight)[1]/step))+" atlas="+atlasWidth+"x"+atlasHeight;
    }
    static int kind(Block block,BlockState state){
        if(block instanceof LeavesBlock||block instanceof VineBlock)return LEAVES;
        if(block instanceof DoublePlantBlock)return state.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.UPPER?UPPER:PLANT;
        if(block instanceof HangingRootsBlock||block instanceof HangingMossBlock||block instanceof CaveVines||block instanceof WeepingVinesBlock||block instanceof WeepingVinesPlantBlock||block instanceof SporeBlossomBlock)return HANGING;
        if(block instanceof LilyPadBlock||block instanceof FlowerBedBlock||block instanceof LeafLitterBlock||block instanceof MushroomBlock)return 0;
        if(block instanceof VegetationBlock||block instanceof TwistingVinesBlock||block instanceof TwistingVinesPlantBlock)return PLANT;
        return 0;
    }
    @SuppressWarnings("unchecked")
    private static List<TextureAtlasSprite> sprites(TextureAtlas atlas){
        try{return (List<TextureAtlasSprite>)SPRITES.get(atlas);}catch(IllegalAccessException error){throw new IllegalStateException(error);}
    }
    /** Sampled pixel rectangle of a sprite: x, y, width, height. */
    private static int[] rect(TextureAtlasSprite sprite,int width,int height){
        int x=Math.round(sprite.getU0()*width),y=Math.round(sprite.getV0()*height);
        return new int[]{x,y,Math.max(1,Math.round(sprite.getU1()*width)-x),Math.max(1,Math.round(sprite.getV1()*height)-y)};
    }
    private static int gcd(int x,int y){x=Math.abs(x);y=Math.abs(y);while(y!=0){int t=x%y;x=y;y=t;}return x;}
    GpuTextureView view(){return view;}
    Map<String,Object> status(){return Map.of("cell",cell,"width",texture.getWidth(0),"height",texture.getHeight(0),"wavingSprites",waving,"glowingSprites",glowing,"probe",probe);}
    @Override public void close(){view.close();texture.close();image.close();}
}
