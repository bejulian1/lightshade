#version 450
#include <sodium:globals.glsl>
#include <minecraft:oit.glsl>
#include <lightshade:frame.glsl>
#include <lightshade:pack.glsl>
// The loader binds the noise volume for every terrain program; both packs use it for water and puddles.
#include <lightshade:noise.glsl>
#include <lightshade:sky.glsl>
#include <lightshade:shadows.glsl>
#include <lightshade:materials.glsl>
layout(location=0) in vec4 lsTint;
layout(location=1) in vec2 lsUV;
layout(location=2) in vec3 lsPosition;
layout(location=3) in vec2 lsLight;
layout(location=4) flat in uint lsFlags;
layout(location=5) in float lsFade;
uniform sampler2D u_BlockTex;
// Sodium's OIT alpha-only layouts intentionally omit LIGHT_GROUP.
#ifndef OIT_ALPHA_ONLY
uniform sampler2D u_LightTex;
#endif
uniform sampler2D LsOpaque; uniform sampler2D LsOpaqueDepth;
#include <lightshade:water.glsl>
#if LIGHTSHADE_AURORA
#include <lightshade:surface.glsl>
#endif
#ifndef OIT_ALPHA_ONLY
layout(location=0) out vec4 fragColor;
#endif

#ifndef OIT_ALPHA_ONLY
// Torch light. Near the camera every light-emitting block is a real point light: a round, warm pool with
// soft falloff, brighter on faces turned toward the flame. Minecraft's own light level decides whether a
// light can reach a face at all: if the level here is lower than a straight path from that light would
// give, the light is behind a wall and does not count, so nothing leaks through. Further out, and where
// more lights exist than the loader sends, a warm curve on Minecraft's level takes over.
vec3 lightTint(int type,float strength){
    if(type==1)return vec3(0.30,0.72,1.0);
    if(type==2)return vec3(1.0,0.16,0.06);
    if(type==3)return vec3(0.78,0.92,1.0);
    // Fire: hotter near the flame, deeper amber at the edge of the pool.
    return mix(vec3(1.0,0.44,0.15),lampColor(),smoothstep(0.25,0.9,strength));
}
vec3 levelLight(float level){
    float lamp=pow(level,4.4)*1.40+pow(level,12.0)*0.45;
    return lightTint(0,level)*lamp;
}
vec3 blockLight(float level,vec3 world,vec3 normal){
    if(level<=0.0)return vec3(0);
    vec3 fallback=levelLight(level);
    int count=int(LsLightInfo.x+0.5);
    float near=1.0-smoothstep(LsLightInfo.y-6.0,LsLightInfo.y,length(lsPosition));
    if(count==0||near<=0.0)return fallback*float(TORCH_BRIGHTNESS);
    vec3 anchor=mod(LsCamera.xyz,vec3(32768));
    vec3 cell=floor(world+normal*0.5);
    vec3 sum=vec3(0);
    for(int i=0;i<count;i++){
        vec4 source=LsLights[i];
        // A torch (level 14) reaches about 8 blocks; the vanilla level still fills in beyond.
        float sourceLevel=mod(source.w,16.0),radius=sourceLevel*0.55+0.5;
        vec3 delta=source.xyz-lsPosition;float dist=length(delta);
        if(dist>=radius)continue;
        float steps=dot(abs(cell-floor(source.xyz+anchor)),vec3(1.0));
        float reach=smoothstep(-2.5,-0.5,level*15.0-(sourceLevel-steps));
        if(reach<=0.0)continue;
        // Inverse-square falloff, windowed to zero at the radius: bright at the flame, a real pool.
        float window=1.0-(dist*dist)/(radius*radius);
        float strength=1.5/(1.0+0.35*dist*dist)*window*window*sourceLevel/15.0;
        float facing=0.35+0.65*max(dot(normal,delta/max(dist,1e-3)),0.0);
#if LIGHTSHADE_AURORA
        // Each flame flickers on its own.
        strength*=0.94+0.06*sin(LsTime.x*(6.0+fract(source.x*7.13+source.z*3.71)*5.0)+source.y);
#endif
        sum+=lightTint(int(source.w/16.0),strength)*strength*facing*reach;
    }
    return mix(fallback,sum+fallback*0.12,near)*float(TORCH_BRIGHTNESS);
}
vec3 heldLight(vec3 normal){
    vec3 light=vec3(0);
    for(int hand=0;hand<2;hand++)if(LsHeldPosition[hand].w>0.0){
        vec3 delta=LsHeldPosition[hand].xyz-lsPosition;float dist=length(delta);
        float falloff=clamp(1.0-dist/LsHeldPosition[hand].w,0.0,1.0);
        light+=LsHeldColor[hand].rgb*falloff*falloff*(.25+.75*max(dot(normal,delta/max(dist,.01)),0.0));
    }
    return light*float(HELD_LIGHTING);
}
#endif

void main(){
    vec4 texel=(lsFlags&1u)!=0u?textureGrad(u_BlockTex,lsUV,dFdx(lsUV),dFdy(lsUV)):textureLod(u_BlockTex,lsUV,0.0);
    float alpha=texel.a*lsTint.a;
    #ifdef ALPHA_CUTOUT
    if(alpha<float(ALPHA_CUTOUT))discard;
    #endif
    bool water=false;for(int i=0;i<3;i++)water=water||lsInside(lsUV,LsWaterRect[i]);
    bool compositeWater=water&&LsRenderSize.z>.5;
    // The water colour below already includes what is behind it: every OIT phase must use that coverage,
    // including depth bounds and transmittance.
    if(compositeWater)alpha=1.0;
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z,alpha);return;
    #else
    vec3 eye=vec3(LsInverseView[3]),toEye=normalize(eye-lsPosition);
    vec3 normal=normalize(cross(dFdx(lsPosition),dFdy(lsPosition)));if(dot(normal,toEye)<0.0)normal=-normal;
    vec3 albedo=lsLinear(texel.rgb*lsTint.rgb);
    vec3 world=stableWorld(lsPosition);
    float skyLevel=clamp(lsLight.y*256.0/240.0,0.0,1.0),lampLevel=clamp(lsLight.x*256.0/240.0,0.0,1.0);
    float sky=skyLevel*skyLevel;
    LsMaterial material=lsMaterial(lsUV);
    bool foliage=material.kind>0;
    vec3 direction=LsSun.y>=0?sunDir():moonDir();
    float direct=max(dot(normal,direction),0.0);
    // Only surfaces that see the sky can be in the sun's shadow: caves and rooms skip the lookup.
    float castShadow=sky>0.004?mix(1.0,lsShadow(lsPosition,normal,foliage),float(SHADOW_STRENGTH)):1.0;
    float clouded=1.0;
    vec3 shading=normal;
    vec3 light;
#if LIGHTSHADE_AURORA
    // Rain: darker, richer ground, with puddles lying on level surfaces under the open sky.
    float wetness=isOverworld()&&!water&&RAIN_PUDDLES>0?LsRenderSize.w*smoothstep(.70,.95,skyLevel):0.0,puddle=0.0;
    float level=smoothstep(.86,.98,normal.y);
    if(wetness>.005){
        puddle=puddleField(world,wetness)*level*wetness;
        float soak=wetness*mix(.55,1.0,level);
        albedo=mix(albedo,pow(albedo,vec3(1.28))*.76,soak);
    }
    float cavity=1.0;
    if(float(SURFACE_DETAIL)>0.0&&!water)shading=detailNormal(normal,cavity);
    shading=normalize(mix(shading,normal,puddle));
    float sunFacing=max(dot(shading,direction),0.0)*smoothstep(-.02,.12,dot(normal,direction));
#if CLOUD_SHADOWS > 0
    // Only where the sun actually lands: faces turned away and cast shadows have nothing to lose.
    if(sky>.004&&isOverworld()&&castShadow>.02&&(sunFacing>.001||foliage||water))clouded=cloudShadow(world,direction);
#endif
    if(foliage){
        // Leaves and grass let light through: a soft wrap all round, and a glow against a low sun.
        float behind=pow(sat(dot(-toEye,direction)),5.0);
        sunFacing=mix(sunFacing,.46+.34*abs(dot(normal,direction)),.75)+behind*float(FOLIAGE_GLOW)*0.8;
        // A low sun warms green leaves toward gold, gently: they stay leaves.
        albedo=mix(albedo,vec3(luminance(albedo))*vec3(1.55,1.05,0.42),twilight()*sat(sunFacing)*castShadow*clouded*.22*sky);
    }
    // The sky lights tops fully, walls with half of it and undersides barely: shading with depth.
    float skyView=.60+.40*shading.y;
    light=ambientLight()*sky*skyView*mix(.76,1.0,castShadow*clouded)*cavity;
    light+=keyLight()*sunFacing*sky*castShadow*clouded;
    // Sunlit ground throws a little warm light back onto walls and undersides.
    light+=keyLight()*vec3(.95,.88,.72)*.055*(1.0-max(shading.y,0.0))*sky*daylight();
#else
    if(foliage){
        float behind=pow(sat(dot(-toEye,direction)),5.0);
        direct=mix(direct,.55+.30*abs(dot(normal,direction)),.65)+behind*float(FOLIAGE_GLOW)*0.6;
    }
    // Vanilla's own face shading is in the vertex colour; the sun adds a clear lean toward lit faces and
    // the cool sky fills the rest.
    float skyView=.68+.32*normal.y;
    light=ambientLight()*sky*skyView*mix(.80,1.0,castShadow);
    light+=keyLight()*(.28+.72*direct)*sky*castShadow;
    light+=keyLight()*vec3(.95,.88,.72)*.04*(1.0-max(normal.y,0.0))*sky*daylight();
#endif
    light+=blockLight(lampLevel,world,normal)+vec3(.0025);
    if(abs(LsTime.w)>.5){
        vec3 nativeLight=lsLinear(texture(u_LightTex,lsLight).rgb);
#if LIGHTSHADE_AURORA
        if(isNether()){
            // Fire from below: the lava sea lights undersides and walls in crimson ember, fading with height.
            float heat=exp(-max(world.y-30.0,0.0)*.024);
            light=nativeLight*vec3(.34,.24,.22)*(.62+.38*max(shading.y,0.0))+vec3(.016,.003,.001);
            light+=vec3(.50,.10,.028)*heat*(.20+.80*sat(.5-.5*shading.y))*(.65+.35*sat(1.0-abs(shading.y)));
            light+=blockLight(lampLevel,world,normal)*.95;
        }else if(isEnd()){
            // A pale, far light from one side gives the islands form; the shade falls to deep violet.
            float pale=max(dot(shading,normalize(vec3(.5,.42,-.45))),0.0);
            albedo=mix(albedo,vec3(luminance(albedo))*vec3(.93,.88,1.0),.72);
            light=nativeLight*(vec3(.040,.028,.105)+vec3(.30,.22,.50)*pale*pale)*cavity+vec3(.004,.002,.010)+blockLight(lampLevel,world,normal)*.7;
        }else light=nativeLight;
#else
        if(isNether())light=nativeLight*vec3(.40,.28,.26)+vec3(.014,.003,.001)+blockLight(lampLevel,world,normal)*.75;
        else if(isEnd())light=nativeLight*vec3(.56,.54,.84)*(.72+.28*max(normal.y,0.0))+vec3(.016,.010,.032)+blockLight(lampLevel,world,normal)*.6;
        else light=nativeLight;
#endif
    }
    // Minecraft's darkest lightmap texel carries Brightness, Night Vision and Darkness: it is the floor,
    // so those settings still work. The End's lightmap never gets dark, so there the floor sits lower.
#if LIGHTSHADE_AURORA
    light=max(light,lsLinear(texture(u_LightTex,vec2(.03125)).rgb)*(isEnd()?.13:.40));
#else
    light=max(light,lsLinear(texture(u_LightTex,vec2(.03125)).rgb)*.40);
#endif
    light+=heldLight(normal);
    vec3 color=albedo*light;
#if LIGHTSHADE_AURORA
    // Sheen. Dry stone and earth catch a little of a low sun; wet ground much more, and it mirrors the sky.
    float mirrorMask=0.0;
    if(!water&&sky>.004){
        vec3 halfway=normalize(direction+toEye);
        float grazing=pow(1.0-max(dot(shading,toEye),0.0),5.0);
        float gloss=mix(36.0,mix(120.0,800.0,puddle),wetness);
        float reach=(.010+grazing*.08)*(1.0-float(foliage)*.7)+wetness*.14+puddle*.9;
        color+=keyLight()*pow(max(dot(shading,halfway),0.0),gloss)*reach*sunFacing*castShadow*clouded*sky;
        float mirror=(.03+.97*grazing)*(wetness*.10+puddle*.80);
        if(foliage)mirror=0.0;
        if(mirror>.002)color=mix(color,skyFogColor(reflect(-toEye,shading)),clamp(mirror,0.0,1.0)*sky);
        mirrorMask=puddle;
    }
#endif
    // Glowing blocks: only the bright texels of the sprite glow (the flame of a torch, not its stick).
    float glow=0.0;
    if(material.emission>0.0){
        glow=material.emission*smoothstep(.30,.78,max(texel.r,max(texel.g,texel.b)));
#if LIGHTSHADE_AURORA
        // Squared, so lava and flames keep their dark veins and hot yellow cores when they are bright.
        color=mix(color,pow(albedo,vec3(1.45))*1.75+albedo*.12,glow);
#else
        color=mix(color,albedo*1.2,glow);
#endif
    }
    if(compositeWater){
        float range=length(lsPosition-eye);float footprint=max(length(dFdx(lsPosition)),length(dFdy(lsPosition)));
        vec2 slope=waterSlope(world,range,footprint,abs(normal.y));
        vec3 surface=normalize(normal-vec3(slope.x,0,slope.y)*sign(normal.y));
        vec2 screen=gl_FragCoord.xy/LsRenderSize.xy,refracted=clamp(screen-slope*.018,1.0/LsRenderSize.xy,1.0-1.0/LsRenderSize.xy);
        float depth=texture(LsOpaqueDepth,refracted).r;float behind=depth>1e-6?length(worldPoint(refracted,depth)-eye):range+70;
        if(behind<range){refracted=screen;depth=texture(LsOpaqueDepth,screen).r;behind=depth>1e-6?length(worldPoint(screen,depth)-eye):range+70;}
        float thickness=clamp(behind-range,0,70);
        // Absorption takes red first: shallow water over sand is clear turquoise, deep water teal and blue.
        vec3 transmission=exp(-thickness*vec3(.30,.080,.058)*float(WATER_DEPTH));
        vec3 glowColor=mix(vec3(.010,.080,.092),lsLinear(lsTint.rgb)*vec3(.06,.11,.13),.25);
        vec3 body=sampleOpaque(refracted)*transmission+glowColor*light*(1.0-transmission);
#if !LIGHTSHADE_AURORA
        // Keep a touch of Minecraft's biome water colour in the body of the water.
        body=mix(body,albedo*light*.9,.22*(1.0-transmission.g));
#endif
        if(float(WATER_CAUSTICS)>0.0&&depth>1e-6&&thickness<20.0&&isOverworld()){
            vec3 bed=stableWorld(worldPoint(refracted,depth));
            body*=1.0+waterCaustics(bed,direction)*float(WATER_CAUSTICS)*1.6*transmission*sky*castShadow*clouded*keyLight()*exp(-thickness*.10);
        }
        // Far away the surface reads calmer: distant water mirrors sky and clouds in one smooth piece.
        vec3 calm=normalize(mix(surface,vec3(0,sign(normal.y),0),smoothstep(14.0,110.0,range)*.85*abs(normal.y)));
        vec3 reflected=reflect(-toEye,calm);
        vec3 environment=lsSkyReflection(normalize(vec3(reflected.x,max(reflected.y,.012),reflected.z)),world,true)*sky;
        vec3 reflection=reflectedScene(lsPosition+normal*.09,reflect(-toEye,surface),environment);
        // Schlick with water's F0 of 0.02, held under 0.9: grazing water mirrors the sky without turning
        // into a flat white sheet.
        float fresnel=clamp((.02+.98*pow(1.0-max(dot(calm,toEye),0.0),5.0))*float(WATER_REFLECTIVITY),0.0,.88);
        if(LsEnvironment.x>.5&&LsEnvironment.x<1.5){
            // Seen from below: the camera-to-surface path is absorbed by the atmosphere pass, and a ray that
            // leaves the water is in air.
            float cosine=max(dot(surface,toEye),0.0),eta=1.333333;
            float transmittedSquared=eta*eta*(1.0-cosine*cosine);
            if(transmittedSquared>=1.0)fresnel=1.0;
            else{
                float cosineAir=sqrt(1.0-transmittedSquared);
                float rs=(eta*cosine-cosineAir)/max(eta*cosine+cosineAir,.0001);
                float rp=(eta*cosineAir-cosine)/max(eta*cosineAir+cosine,.0001);
                fresnel=clamp((rs*rs+rp*rp)*.5,0,1);
                vec3 transmitted=normalize(refract(-toEye,surface,eta));
                body=depth>1e-6?sampleOpaque(refracted):lsSkyReflection(transmitted,world,true);
            }
            environment=waterFogColor();reflection=reflectedScene(lsPosition+normal*.05,reflected,environment);
        }
        color=mix(body,reflection,fresnel);
        // A thin line of foam where the water meets the land, breathing in and out with the swell.
        if(float(WATER_FOAM)>0.0&&LsEnvironment.x<.5&&abs(normal.y)>.5&&depth>1e-6){
            float shallow=thickness*max(abs(toEye.y),.12);
            float swell=sin(LsTime.x*.6+dot(world.xz,vec2(.21,.13)))*.10;
            float lace=lsNoise3(vec3(world.xz/6.5,LsTime.x*.018)).g*.7+lsNoise3(vec3(world.xz/2.3,LsTime.x*.031)).b*.3;
            float foam=smoothstep(.62,.34,lace+shallow*.62-swell)*(1.0-smoothstep(.18,.55,shallow));
            color=mix(color,vec3(.80,.85,.88)*(light+.02),clamp(foam*float(WATER_FOAM),0.0,1.0)*.75);
        }
        // Sun glint: a tight sparkle where the ripples face the sun, over a soft sheen.
        vec3 halfway=normalize(direction+toEye);
        float facingSun=max(dot(surface,halfway),0.0);
#if LIGHTSHADE_AURORA
        color+=keyLight()*(pow(facingSun,700.0)*1.5+pow(facingSun,110.0)*.05)*castShadow*clouded*sky;
#else
        color+=keyLight()*(pow(facingSun,400.0)*.9+pow(facingSun,90.0)*.04)*castShadow*sky;
#endif
    }
    float range=length(lsPosition-eye),cylinder=length((lsPosition-eye).xz);
    float renderFog=smoothstep(u_RenderFog.x,u_RenderFog.y,cylinder);
    // The sky colour is costly per fragment; most of the world is nearer than the fog.
    float fogAmount=max(renderFog,1.0-lsFade);
    if(fogAmount>0.001)color=mix(color,skyFogColor(normalize(lsPosition-eye)),fogAmount);
    bool ordinaryDimension=LsEnvironment.x<.5&&((isNether()&&abs(u_EnvironmentFog.x-10)<.01&&abs(u_EnvironmentFog.y-96)<.01)||(isEnd()&&abs(u_EnvironmentFog.y-96)<.01));
    if(!ordinaryDimension&&(LsEnvironment.x<.5||LsEnvironment.x>1.5))color=mix(color,lsLinear(u_FogColor.rgb),smoothstep(u_EnvironmentFog.x,u_EnvironmentFog.y,range));
    vec4 outputColor=vec4(lsDisplay(color),alpha);
    #ifndef OIT
    // Glowing pixels are marked in alpha (opaque layers only): bloom then comes from real light sources,
    // not from anything bright and yellow in the sun.
    if(glow>0.0&&alpha>=0.999&&!compositeWater)outputColor.a=1.0-0.5*glow;
    #if LIGHTSHADE_AURORA
    // Below one half the mark means something else: this pixel mirrors the scene (atmosphere.fsh).
    else if(mirrorMask>.01&&alpha>=0.999&&!compositeWater)outputColor.a=.49*(1.0-mirrorMask);
    #endif
    #endif
    #ifdef OIT_ACCUMULATE
    outputColor=sampleColorForAccumulation(outputColor);
    #endif
    fragColor=outputColor;
    #endif
}
