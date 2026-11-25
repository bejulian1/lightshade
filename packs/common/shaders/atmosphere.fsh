#version 450
#include <lightshade:sky.glsl>
// Sky, contact shading, distance haze, volumetric light and clouds over the
// finished world. Writes linear light (RGBA16F) for the grade in display.fsh.
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Scene;uniform sampler2D Depth;uniform sampler2D Volume;uniform sampler2D Clouds;
#if LIGHTSHADE_AURORA
uniform sampler2D OpaqueDepth;
// The water optics, reading this pass's snapshot: used for puddles.
#define LsOpaque Scene
#define LsOpaqueDepth Depth
#include <lightshade:water.glsl>
#undef LsOpaque
#undef LsOpaqueDepth
#endif
#ifndef TORCH_HAZE
#define TORCH_HAZE 0.0
#endif
#define LIGHT_AO_RADIUS 0.9
// The connected side of each pixel, not a difference across a silhouette.
vec3 contactNormal(vec3 p,out float confidence){
    ivec2 size=textureSize(Depth,0),pixel=ivec2(gl_FragCoord.xy);
    vec3 neighbors[4];float separation[4];
    const ivec2 offsets[4]=ivec2[4](ivec2(-1,0),ivec2(1,0),ivec2(0,-1),ivec2(0,1));
    for(int i=0;i<4;i++){
        ivec2 at=clamp(pixel+offsets[i],ivec2(0),size-1);
        float z=texelFetch(Depth,at,0).r;
        neighbors[i]=unproject((vec2(at)+0.5)/vec2(size),max(z,1e-6));
        separation[i]=z>1e-6?abs(neighbors[i].z-p.z):1e10;
    }
    vec3 dx=separation[0]<separation[1]?p-neighbors[0]:neighbors[1]-p;
    vec3 dy=separation[2]<separation[3]?p-neighbors[2]:neighbors[3]-p;
    vec3 crossNormal=cross(dx,dy);float normalLength=length(crossNormal);
    float pixelWorld=2.0*abs(p.z-opticalEye().z)/max(abs(LsProjection[1][1])*LsRenderSize.y,1.0);
    float stretch=max(length(dx),length(dy))/max(pixelWorld,1e-5);
    confidence=(normalLength>1e-10?1.0:0.0)*(1.0-smoothstep(4.0,12.0,stretch));
    vec3 normal=crossNormal/max(normalLength,1e-10);
    return dot(normal,opticalEye()-p)<0.0?-normal:normal;
}
float ambientOcclusion(vec3 p){
#if AMBIENT_OCCLUSION > 0
    const int count=AMBIENT_OCCLUSION==1?6:10;
    float distance=length(p-opticalEye());
    float radius=LIGHT_AO_RADIUS;
    float projectedRadius=abs(LsProjection[1][1])*LsRenderSize.y*0.5*radius/max(abs(p.z-opticalEye().z),0.1);
    float coverage=smoothstep(0.75,2.5,projectedRadius)*(1.0-smoothstep(64.0,112.0,distance));
    if(coverage<=0.0)return 1.0;
    float confidence;vec3 normal=contactNormal(p,confidence);
    if(confidence<=0.0)return 1.0;
    float radiusPixels=clamp(projectedRadius,2.0,64.0),sum=0.0;
    ivec2 size=textureSize(Depth,0);
    for(int i=0;i<count;i++){
        float angle=(float(i)+0.5)*2.39996323;
        float tapRadius=mix(0.12,1.0,(float(i)+0.5)/float(count))*radiusPixels;
        vec2 st=uv+vec2(cos(angle),sin(angle))*tapRadius/LsRenderSize.xy;
        if(any(lessThan(st,vec2(0)))||any(greaterThan(st,vec2(1))))continue;
        ivec2 at=clamp(ivec2(st*vec2(size)),ivec2(0),size-1);
        float d=texelFetch(Depth,at,0).r;
        if(d<1e-6)continue;
        vec3 v=unproject((vec2(at)+0.5)/vec2(size),d)-p;float len=length(v);
        if(len<0.01||len>=radius)continue;
        float facing=max((dot(normal,v)-max(0.012,radius*0.025))/len-0.055,0.0);
        sum+=facing*(1.0-smoothstep(radius*0.15,radius,len));
    }
    float occlusion=min(sum/float(count)*3.0,0.55);
    return 1.0-occlusion*confidence*coverage;
#else
    return 1.0;
#endif
}
// Edge-aware 2x2 upsampling of a half-resolution target.
vec4 upsampled(sampler2D image,float receiverDistance,vec4 empty){
    ivec2 size=textureSize(image,0);vec2 p=uv*vec2(size)-.5,f=fract(p);ivec2 base=ivec2(floor(p));
    vec4 sum=vec4(0);float weights=0,tolerance=max(.75,receiverDistance*.05);
    for(int y=0;y<2;y++)for(int x=0;x<2;x++){
        ivec2 at=clamp(base+ivec2(x,y),ivec2(0),size-1);vec2 st=(vec2(at)+.5)/vec2(size);
        float z=texture(Depth,st).r;float d=z<1e-6?10000.0:length(unproject(st,z)-opticalEye());
        float w=(x==0?1-f.x:f.x)*(y==0?1-f.y:f.y);w*=1-smoothstep(tolerance,tolerance*2,abs(d-receiverDistance));
        sum+=texelFetch(image,at,0)*w;weights+=w;
    }
    return weights>.001?sum/weights:empty;
}
#if LIGHTSHADE_AURORA
// Light from nearby torches scattered in the air along the view ray. The integral of inverse-square
// light along a line has a closed form, so each light costs a few operations and needs no marching;
// a ray that ends at a wall collects nothing from a torch behind it.
vec3 torchHaze(vec3 ray,float reach){
    vec3 sum=vec3(0);vec3 eye=cameraOffset();
    int count=min(int(LsLightInfo.x+0.5),8);
    for(int i=0;i<count;i++){
        vec4 source=LsLights[i];vec3 toLight=source.xyz-eye;
        float along=dot(toLight,ray),square=max(dot(toLight,toLight)-along*along,0.02),side=sqrt(square);
        float amount=(atan((reach-along)/side)+atan(along/side))/side;
        float kind=floor(source.w/16.0);
        vec3 tint=kind<.5?vec3(1.0,.50,.18):kind<1.5?vec3(.24,.62,1.0):kind<2.5?vec3(1.0,.14,.05):vec3(.70,.88,1.0);
        // Limited in range, so a lit base does not fog over.
        float near=1.0-smoothstep(5.0,22.0,length(toLight));
        // The closed form is for endless air; this keeps the glow a halo round the flame, not a veil.
        sum+=tint*amount*exp(-side*.95)*near*mod(source.w,16.0)/15.0;
    }
    return sum;
}
// Rain as streaks on cylinders around the camera, so they stay put in the world as you turn and are
// hidden by whatever stands in front of them. Each column has its own offset, speed and gaps, and the
// whole of it comes in drifting curtains, so it never reads as a grid. Vanilla's blue columns are off
// ("ownsRain").
vec3 rainStreaks(vec3 ray,float surface){
    float amount=rainAmount()*sat(LsRenderSize.w*1.6)*smoothstep(.1,.7,eyeSky());
    if(amount<=.01)return vec3(0);
    float level=max(length(ray.xz),.05),angle=atan(ray.x,ray.z),rise=ray.y/level,sum=0.0;
    // Looking steeply up or down the columns converge: fade them out there.
    float steep=1.0-smoothstep(.18,.62,abs(ray.y));
    if(steep<=0.0)return vec3(0);
    float curtain=smoothstep(.36,.70,lsNoise3(vec3(angle*.19,rise*.08+LsTime.x*.017,LsTime.x*.031)).a);
    for(int layer=0;layer<3;layer++){
        float radius=4.5*pow(2.6,float(layer)),along=radius/level;
        if(along>=surface)break;
        float lean=rise*radius*.030;
        float u=(angle+lean/radius)*(40.0+float(layer)*46.0);
        for(int sheet=0;sheet<2;sheet++){
            float shifted=u*(sheet==0?1.0:1.37)+float(sheet)*17.3+float(layer)*5.1,column=floor(shifted);
            vec3 r=fract(vec3(column*.1031,column*.1137,column*.0973+float(layer+sheet*3)));
            r=fract(r+dot(r,r.yzx+19.19));
            r=fract((r+r.yzx)*r.zxy*vec3(43.7,37.3,29.1));
            float v=rise*radius*.30+LsTime.x*(1.7+r.x*1.5)+r.y*31.0;
            float segment=floor(v),phase=v-segment;
            float h=fract(sin(segment*12.9898+column*78.233)*43758.5453),g=fract(h*7.13),k=fract(h*19.7);
            if(h>.20)continue;
            float x=fract(shifted)-.18-.64*g;
            float width=layer==0?.045:.065;
            float len=.10+.34*k,head=.15+.6*g;
            float y=(phase-head)/len;
            sum+=exp(-x*x/(width*width))*smoothstep(0.0,.25,y)*smoothstep(1.0,.4,y)*(.35+.65*k)*(1.0-float(layer)*.26);
        }
    }
    // A pale tint from the sky; against open sky itself they all but vanish.
    float against=surface>=1000.0?.16:1.0;
    return mix(vec3(.60,.66,.76),skyFogColor(vec3(0,1,0))*2.0,.5)*sum*amount*curtain*steep*against*.125;
}
#endif
void main(){
    float depth=texture(Depth,uv).r;vec3 ray=worldRay(uv),point=unproject(uv,max(depth,1e-6));float distance=length(point-opticalEye());
    vec4 sceneTexel=texture(Scene,uv);
    vec3 scene=toLinear(sceneTexel.rgb);bool known=isOverworld()||isNether()||isEnd();
    // terrain.fsh marks glowing pixels with alpha below 1; bloom reads the mask from our alpha.
    float glow=depth>1e-6&&sceneTexel.a>=.495?clamp((1.0-sceneTexel.a)*2.0,0.0,1.0):0.0;
#if LIGHTSHADE_AURORA
    float mirror=depth>1e-6&&sceneTexel.a<.495?1.0-sceneTexel.a/.49:0.0;
#endif
    if(depth<1e-6&&known)scene=lsSkyBase(ray,true);
    else if(depth>1e-6){
#if LIGHTSHADE_AURORA
        // Glowing blocks and sun glints on water really are brighter than the 8-bit world can store.
        scene*=1.0+glow*float(GLOW_POWER);
        if(depth>texture(OpaqueDepth,uv).r+1e-7){
            float peak=max(scene.r,max(scene.g,scene.b));
            float bearing=mix(1.0,smoothstep(.97,.997,dot(normalize(ray.xz+vec2(1e-5,0)),normalize(sunDir().xz+vec2(1e-5,0)))),goldenHour());
            scene*=1.0+smoothstep(.90,.998,peak)*1.6*bearing;
        }
        if(mirror>0.0&&isOverworld()){
            // Puddles: the same reflection as water, over this pass's image of the world.
            vec3 at=worldPoint(uv,depth);
            vec2 ripple=rainRipples(stableWorld(at).xz,LsTime.x)*rainAmount()*(1.0-smoothstep(4.0,28.0,distance));
            vec3 normal=normalize(vec3(-ripple.x,1.0,-ripple.y)),reflected=reflect(ray,normal);
            vec3 hit=reflectedScene(at+vec3(0,.05,0),reflected,skyFogColor(reflected));
            float fresnel=clamp(.24+1.0*pow(1.0-max(dot(normal,-ray),0.0),3.5),0.0,1.0);
            scene=mix(scene*(1.0-.45*mirror),hit,fresnel*mirror);
        }
        if(LsEnvironment.x>.5&&LsEnvironment.x<1.5&&isOverworld()&&float(WATER_CAUSTICS)>0.0&&distance<40.0){
            // Under water the light webs fall on everything the sun reaches.
            scene*=1.0+waterCaustics(stableWorld(worldPoint(uv,depth)),sunDir())*float(WATER_CAUSTICS)*7.0*daylight()*eyeSky()*(1.0-smoothstep(16.0,40.0,distance));
        }
#endif
        scene*=mix(ambientOcclusion(point),1.0,smoothstep(.65,.96,luminance(scene)));
        float midHeight=LsCamera.y+worldPoint(uv,depth).y*.5,density=0;vec3 fog=skyFogColor(ray);
        if(isOverworld()){
#if LIGHTSHADE_AURORA
            density=(.00026+exp(-max(midHeight-62.0,0.0)*.045)*.00034+rainAmount()*.0022);
#else
            // Aerial perspective: far ridges take a soft tint of the sky, thicker in the low air.
            density=(.00020+exp(-max(midHeight-62.0,0.0)*.045)*.00022+rainAmount()*.0018);
#endif
            // Dark places do not glow with haze: the fog is lit like the scene.
#if LIGHTSHADE_AURORA
            // A low sun thickens the haze a little, so far ridges soften into the sky without
            // the near land losing its shape.
            density*=1.0+twilight()*0.7;
#endif
            density*=mix(.12,1.0,smoothstep(.008,.09,luminance(scene)))*mix(.25,1.0,eyeSky());
        }
#if LIGHTSHADE_AURORA
        else if(isNether())density=.0052;else if(isEnd())density=.0030;
#else
        else if(isNether())density=.0060;else if(isEnd())density=.0020;
#endif
        float amount=(1-exp(-distance*density*float(ATMOSPHERE)))*smoothstep(12.0,32.0,distance);scene=mix(scene,fog,amount);
        float farEnd=LsFogDistances.z,farStart=LsFogDistances.y;
        bool ordinaryDimension=LsEnvironment.x<.5&&((isNether()&&abs(LsFogColor.w-10)<.01&&abs(LsFogDistances.x-96)<.01)||(isEnd()&&abs(LsFogDistances.x-96)<.01));
        if(ordinaryDimension){farEnd=min(farEnd,LsFogDistances.x);farStart=min(farStart,farEnd*.75);}
        vec3 relative=worldPoint(uv,depth)-cameraOffset();float cylinder=max(length(relative.xz),abs(relative.y));
        if(known&&farEnd>farStart+.01)scene=mix(scene,fog,smoothstep(farStart,farEnd,cylinder));
    }
    if(LsEnvironment.x>.5){float absorption=1-exp(-min(distance,300.0)*(LsEnvironment.x<1.5?.042:.85));scene=mix(scene,LsEnvironment.x<1.5?waterFogColor():toLinear(LsFogColor.rgb),absorption);
#if VOLUMETRIC_LIGHT > 0 && LIGHTSHADE_AURORA
        // Shafts of sunlight down through the water.
        if(LsEnvironment.x<1.5&&isOverworld())scene+=upsampled(Volume,depth<1e-6?10000.0:distance,vec4(0,0,0,1)).rgb;
#endif
    }
#if VOLUMETRIC_LIGHT > 0
#if LIGHTSHADE_AURORA
    else if(known){vec4 volume=upsampled(Volume,depth<1e-6?10000.0:distance,vec4(0,0,0,1));scene=scene*clamp(volume.a,0,1)+volume.rgb;}
#else
    else if(isOverworld()){vec4 volume=upsampled(Volume,depth<1e-6?10000.0:distance,vec4(0,0,0,1));scene=scene*clamp(volume.a,0,1)+volume.rgb;}
#endif
#endif
#if LIGHTSHADE_AURORA
    // Daylight drowns this glow, so it stops being computed once the sun is up.
    float hazeLight=float(TORCH_HAZE)*(isOverworld()?1.0-daylight():1.0)*(LsLightInfo.x>.5&&LsEnvironment.x<.5?1.0:0.0);
    if(hazeLight>.005){
        float reach=depth<1e-6?60.0:min(distance,60.0);
        scene+=torchHaze(ray,reach)*hazeLight*.075;
    }
#endif
#if CLOUD_QUALITY > 0
#if LIGHTSHADE_AURORA
    if(isOverworld()&&LsEnvironment.x<.5){
        vec4 clouds;
        if(depth<1e-6){
            // Open sky has no edges to protect: a small tent filter melts the march's per-pixel offsets.
            vec2 t=.5/vec2(textureSize(Clouds,0));
            clouds=(texture(Clouds,uv+t)+texture(Clouds,uv-t)+texture(Clouds,uv+vec2(t.x,-t.y))+texture(Clouds,uv+vec2(-t.x,t.y)))*.25;
        }else clouds=upsampled(Clouds,distance,vec4(0));
        scene=scene*(1.0-clamp(clouds.a,0,1))+clouds.rgb;
    }
#else
    if(isOverworld()&&LsEnvironment.x<.5){vec4 clouds=upsampled(Clouds,depth<1e-6?10000.0:distance,vec4(0));scene=scene*(1.0-clamp(clouds.a,0,1))+clouds.rgb;}
#endif
#endif
#if LIGHTSHADE_AURORA
    if(isOverworld()&&LsEnvironment.x<.5)scene+=rainStreaks(ray,depth<1e-6?1000.0:distance);
#endif
    bool ordinaryDimension=LsEnvironment.x<.5&&((isNether()&&abs(LsFogColor.w-10)<.01&&abs(LsFogDistances.x-96)<.01)||(isEnd()&&abs(LsFogDistances.x-96)<.01));
    if(!ordinaryDimension&&LsFogDistances.x<LsFogDistances.z-.5&&LsEnvironment.x<.5){float fogDistance=depth<1e-6?LsFogDistances.z:distance;float amount=clamp((fogDistance-LsFogColor.w)/max(LsFogDistances.x-LsFogColor.w,.001),0,1);scene=mix(scene,toLinear(LsFogColor.rgb),amount);}
    color=vec4(max(scene,vec3(0)),glow);
}
