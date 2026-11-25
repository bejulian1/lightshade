package no.julian.lightshade.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Random;
import java.util.stream.IntStream;

/** A tiling 3D noise volume kept as a 2D atlas of 64 slices, so shaders pay two bilinear fetches where
 * hashed lattice noise costs eight hashes per octave, and get Worley billows that value noise cannot
 * give. 8x8 tiles of 64x64 with a one-texel wrapped gutter: the volume is 62x62x64 (noise.glsl reads it).
 * R Perlin-Worley (cloud body), G Worley at 6 cells, B Worley at 10 cells, A Perlin at 5 cells. All
 * channels tile. Generated once from a fixed seed, on the CPU, and shared by every pack load. */
final class NoiseVolume implements AutoCloseable {
    static final int SIZE=512,TILE=64,TILES=8,AREA=TILE-2,SLICES=TILES*TILES;
    private static int[] pixels;
    private final NativeImage image;
    private final GpuTexture texture;
    private final GpuTextureView view;

    NoiseVolume(){
        if(pixels==null)pixels=generate();
        image=new NativeImage(SIZE,SIZE,true);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++)image.setPixel(x,y,pixels[y*SIZE+x]);
        var device=RenderSystem.getDevice();
        texture=device.createTexture("Lightshade noise",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
        device.createCommandEncoder().writeToTexture(texture,image);
        view=device.createTextureView(texture);
    }
    GpuTextureView view(){return view;}
    @Override public void close(){view.close();texture.close();image.close();}

    private static int[] generate(){
        Worley[] worley={new Worley(4,11),new Worley(6,12),new Worley(8,13),new Worley(10,14),new Worley(12,15),new Worley(16,16),new Worley(20,17),new Worley(24,18),new Worley(40,19)};
        Perlin[] perlin={new Perlin(4,21),new Perlin(5,22),new Perlin(8,23),new Perlin(10,24),new Perlin(16,25),new Perlin(20,26),new Perlin(32,27),new Perlin(40,28),new Perlin(64,29),new Perlin(80,30)};
        int count=AREA*AREA*SLICES;float[][] values=new float[4][count];
        IntStream.range(0,SLICES).parallel().forEach(s->{
            float z=(float)s/SLICES;
            for(int y=0;y<AREA;y++)for(int x=0;x<AREA;x++){
                float u=(float)x/AREA,v=(float)y/AREA;int at=(s*AREA+y)*AREA+x;
                float body=perlin[0].at(u,v,z)*.5f+perlin[2].at(u,v,z)*.25f+perlin[4].at(u,v,z)*.125f+perlin[6].at(u,v,z)*.0625f;
                float cells=worley[0].at(u,v,z)*.625f+worley[2].at(u,v,z)*.25f+worley[5].at(u,v,z)*.125f;
                body=body*.5f+.5f;
                // Perlin-Worley: the Perlin body, its low values dug out along Worley's billows.
                values[0][at]=cells+(body-1)*(1-cells);
                values[1][at]=worley[1].at(u,v,z)*.625f+worley[4].at(u,v,z)*.25f+worley[7].at(u,v,z)*.125f;
                values[2][at]=worley[3].at(u,v,z)*.625f+worley[6].at(u,v,z)*.25f+worley[8].at(u,v,z)*.125f;
                values[3][at]=perlin[1].at(u,v,z)*.5f+perlin[3].at(u,v,z)*.25f+perlin[5].at(u,v,z)*.125f+perlin[7].at(u,v,z)*.0625f+perlin[9].at(u,v,z)*.03125f;
            }
        });
        for(float[] channel:values)normalize(channel);
        int[] result=new int[SIZE*SIZE];
        for(int s=0;s<SLICES;s++){
            int ox=(s%TILES)*TILE,oy=(s/TILES)*TILE;
            for(int y=0;y<TILE;y++)for(int x=0;x<TILE;x++){
                // Texel 0 and 63 repeat the opposite edge, so hardware bilinear stays seamless.
                int ax=Math.floorMod(x-1,AREA),ay=Math.floorMod(y-1,AREA),at=(s*AREA+ay)*AREA+ax;
                result[(oy+y)*SIZE+ox+x]=byteOf(values[3][at])<<24|byteOf(values[0][at])<<16|byteOf(values[1][at])<<8|byteOf(values[2][at]);
            }
        }
        return result;
    }
    private static int byteOf(float value){return Math.round(Math.max(0,Math.min(1,value))*255);}
    /** Stretches a channel to the full range, ignoring the outermost 0.2% so the 8 bits are well used. */
    private static void normalize(float[] channel){
        float[] sorted=channel.clone();java.util.Arrays.sort(sorted);
        float low=sorted[sorted.length/500],high=sorted[sorted.length-1-sorted.length/500],scale=1/Math.max(high-low,1e-5f);
        for(int i=0;i<channel.length;i++)channel[i]=(channel[i]-low)*scale;
    }
    /** Tiling Worley noise over the unit cube: 1 at a feature point, falling toward 0 at a cell's far side. */
    private static final class Worley{
        final int cells;final float[] points;
        Worley(int cells,long seed){
            this.cells=cells;points=new float[cells*cells*cells*3];Random random=new Random(seed);
            for(int i=0;i<points.length;i++)points[i]=random.nextFloat();
        }
        float at(float u,float v,float w){
            float x=u*cells,y=v*cells,z=w*cells;int cx=(int)x,cy=(int)y,cz=(int)z;float best=9;
            for(int k=-1;k<=1;k++)for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
                int nx=Math.floorMod(cx+i,cells),ny=Math.floorMod(cy+j,cells),nz=Math.floorMod(cz+k,cells),at=(nz*cells+ny)*cells*3+nx*3;
                float dx=cx+i+points[at]-x,dy=cy+j+points[at+1]-y,dz=cz+k+points[at+2]-z,d=dx*dx+dy*dy+dz*dz;
                if(d<best)best=d;
            }
            return 1-Math.min((float)Math.sqrt(best),1);
        }
    }
    /** Tiling Perlin gradient noise over the unit cube, about -1..1. */
    private static final class Perlin{
        final int cells;final float[] gradients;
        Perlin(int cells,long seed){
            this.cells=cells;gradients=new float[cells*cells*cells*3];Random random=new Random(seed);
            for(int i=0;i<gradients.length;i+=3){
                float x=random.nextFloat()*2-1,y=random.nextFloat()*2-1,z=random.nextFloat()*2-1,l=Math.max((float)Math.sqrt(x*x+y*y+z*z),1e-4f);
                gradients[i]=x/l;gradients[i+1]=y/l;gradients[i+2]=z/l;
            }
        }
        private float dot(int cx,int cy,int cz,float dx,float dy,float dz){
            int at=(Math.floorMod(cz,cells)*cells+Math.floorMod(cy,cells))*cells*3+Math.floorMod(cx,cells)*3;
            return gradients[at]*dx+gradients[at+1]*dy+gradients[at+2]*dz;
        }
        private static float fade(float t){return t*t*t*(t*(t*6-15)+10);}
        private static float mix(float a,float b,float t){return a+(b-a)*t;}
        float at(float u,float v,float w){
            float x=u*cells,y=v*cells,z=w*cells;int cx=(int)x,cy=(int)y,cz=(int)z;float fx=x-cx,fy=y-cy,fz=z-cz,a=fade(fx),b=fade(fy),c=fade(fz);
            float x00=mix(dot(cx,cy,cz,fx,fy,fz),dot(cx+1,cy,cz,fx-1,fy,fz),a),x10=mix(dot(cx,cy+1,cz,fx,fy-1,fz),dot(cx+1,cy+1,cz,fx-1,fy-1,fz),a);
            float x01=mix(dot(cx,cy,cz+1,fx,fy,fz-1),dot(cx+1,cy,cz+1,fx-1,fy,fz-1),a),x11=mix(dot(cx,cy+1,cz+1,fx,fy-1,fz-1),dot(cx+1,cy+1,cz+1,fx-1,fy-1,fz-1),a);
            return mix(mix(x00,x10,b),mix(x01,x11,b),c)*1.5f;
        }
    }
}
