#version 450
#include <lightshade:atmospheric.glsl>
#include <lightshade:shadows.glsl>
// Volumetric light: sunlight scattered in the air along each view ray, with the
// shadow map deciding which parts of the air the sun can reach. Half resolution.
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Depth;
#if LIGHTSHADE_AURORA
#include <lightshade:noise.glsl>
#endif
float visibilityAt(vec3 point){
#if SHADOWS > 0
    vec4 p=LsLightMatrix*vec4(point,1);vec3 q=p.xyz/p.w;vec2 st=q.xy*.5+.5;
    if(any(lessThan(st,vec2(.001)))||any(greaterThan(st,vec2(.999)))||q.z<=0.0||q.z>=1.0||LsShadowInfo.x<.5)return 1.0;
    return q.z-.08/LsShadowInfo.w<=texture(LsShadowMap,st).r?1.0:0.0;
#else
    return 1.0;
#endif
}
#if LIGHTSHADE_AURORA && VOLUMETRIC_LIGHT > 0
// Sun shafts seen from under water: the wave pattern on the surface, carried down along the light.
void underwaterShafts(){
    float depth=texture(Depth,uv).r;vec3 ray=worldRay(uv);
    float reach=depth<1e-6?34.0:min(length(worldPoint(uv,depth)-cameraOffset()),34.0);
    const int count=VOLUMETRIC_LIGHT==1?10:16;
    float stepLength=reach/float(count),jitter=fract(dot(gl_FragCoord.xy,vec2(.75487766,.56984029))),sum=0.0;
    vec3 sun=sunDir(),slant=sun/max(sun.y,.3);
    for(int i=0;i<count;i++){
        vec3 p=stableWorld(cameraOffset()+ray*((float(i)+jitter)*stepLength));
        vec2 at=p.xz-slant.xz*p.y;
        float shaft=lsNoise3(vec3(at/26.0,LsTime.x*.012)).a;
        sum+=pow(smoothstep(.45,.82,shaft),2.0)*exp(-stepLength*float(i)*.075);
    }
    color=vec4(vec3(.18,.62,.78)*sum*stepLength*.022*daylight()*eyeSky()*float(VOLUMETRIC_STRENGTH),1);
}
// Drifting smoke in the Nether and pale dust in the End, marched through the noise volume.
void dimensionAir(){
    float depth=texture(Depth,uv).r;vec3 ray=worldRay(uv);bool nether=isNether();
    // Land past the environment fog is already fully fogged, so the air must end there for land and
    // sky alike: marching further over open sky would draw the land's outline into the smoke.
    float limit=nether?min(LsFogDistances.x,170.0):170.0;
    float reach=depth<1e-6?limit:min(length(worldPoint(uv,depth)-cameraOffset()),limit);
    const int count=VOLUMETRIC_LIGHT==1?12:20;
    float stepLength=reach/float(count),jitter=fract(dot(gl_FragCoord.xy,vec2(.75487766,.56984029))),transmittance=1.0;
    vec3 scatter=vec3(0);vec3 flow=vec3(LsTime.x*.9,LsTime.x*(nether?1.4:.3),LsTime.x*.5);
    for(int i=0;i<count;i++){
        vec3 p=stableWorld(cameraOffset()+ray*((float(i)+jitter)*stepLength));
        vec4 n=lsNoise3((p+flow)/vec3(150.0,88.0,150.0));
        float thick=smoothstep(.42,.80,n.a*.72+n.g*.28);
        float density,height;vec3 tint;
        if(nether){
            // Hot and dense over the lava, thin and dark high under the roof.
            height=exp(-max(p.y-32.0,0.0)*.030);
            density=(.0012+.0260*thick*(.30+.70*height));
            tint=mix(vec3(.040,.008,.006),vec3(.95,.20,.06),height*height);
        }else{
            height=exp(-abs(p.y-58.0)*.022);
            density=(.0006+.0090*thick*height);
            tint=mix(vec3(.080,.045,.180),vec3(.10,.10,.30),n.b);
        }
        float segment=exp(-density*stepLength);
        scatter+=transmittance*(1.0-segment)*tint;
        transmittance*=segment;
    }
    color=vec4(scatter*float(VOLUMETRIC_STRENGTH),mix(1.0,transmittance,float(VOLUMETRIC_STRENGTH)));
}
#endif
void main(){
    color=vec4(0,0,0,1);
#if LIGHTSHADE_AURORA && VOLUMETRIC_LIGHT > 0
    if(LsEnvironment.x<.5&&(isNether()||isEnd())){dimensionAir();return;}
    if(isOverworld()&&LsEnvironment.x>.5&&LsEnvironment.x<1.5&&sunDir().y>.02){underwaterShafts();return;}
#endif
#if VOLUMETRIC_LIGHT > 0
    if(!isOverworld()||LsEnvironment.x>.5||sunDir().y<.015)return;
    float depth=texture(Depth,uv).r;vec3 ray=worldRay(uv);
    float reach=float(SHADOW_DISTANCE);
    float distance=depth<1e-6?reach:min(length(worldPoint(uv,depth)-cameraOffset()),reach);
    const int count=VOLUMETRIC_LIGHT==1?10:18;float stepLength=distance/float(count),transmittance=1.0;vec3 scatter=vec3(0);
    float cosine=dot(ray,sunDir()),g=.72,denominator=max(1.0+g*g-2.0*g*cosine,.02);
    float phase=.07957747*(1.0-g*g)/(denominator*sqrt(denominator));
    // A fixed per-pixel offset: the edge-aware upsampling smooths it out.
    float jitter=fract(dot(gl_FragCoord.xy,vec2(.75487766,.56984029)));
    // A low sun lights more air on its way, but only a little more: no yellow smog over the land.
    vec3 light=keyLight()*mix(.35,1.0,eyeSky())*(1.0+twilight()*0.5);
    for(int i=0;i<count;i++){
        vec3 p=cameraOffset()+ray*((float(i)+jitter)*stepLength);vec3 world=stableWorld(p);
        float height=.2+.8*exp(-max(world.y-64.0,0.0)/64.0);
        float density=(.0008+rainAmount()*.0015)*height;
        float segment=exp(-density*stepLength);
        scatter+=transmittance*(1.0-segment)*light*(phase*6.0+.05)*visibilityAt(p);
        transmittance*=segment;
    }
    color=vec4(scatter*float(VOLUMETRIC_STRENGTH),mix(1.0,transmittance,float(VOLUMETRIC_STRENGTH)));
#endif
}
