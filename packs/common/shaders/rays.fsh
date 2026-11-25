#version 450
#include <lightshade:atmospheric.glsl>
// Godrays: light shafts from the sun or moon through gaps in leaves, hills and
// buildings, traced across the screen toward the light. Quarter resolution;
// the display pass adds them with a bilinear upsample.
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Depth;
void main(){
    color=vec4(0,0,0,1);
#if GODRAYS > 0
    if(!isOverworld()||LsEnvironment.x>.5)return;
    bool sun=sunDir().y>-0.02;
    vec3 light=sun?sunDir():moonDir();
    vec4 clip=LsProjection*vec4(mat3(LsView)*light,0.0);
    if(clip.w<=1e-4)return;
    vec2 source=clip.xy/clip.w*0.5+0.5;
    // Fade out when the light is far off screen or below the horizon.
    float onScreen=1.0-smoothstep(0.35,1.0,max(abs(source.x-0.5),abs(source.y-0.5))-0.25);
    float strength=onScreen*smoothstep(-0.02,0.10,light.y)*(1.0-rainAmount()*0.85)*mix(.15,1.0,eyeSky());
    if(strength<=0.001)return;
#if LIGHTSHADE_AURORA
    const int count=GODRAYS==1?16:32;
#else
    const int count=GODRAYS==1?12:20;
#endif
    vec2 delta=(source-uv)/float(count)*0.92;
    float jitter=fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))*52.9829189);
    vec2 p=uv+delta*jitter;
    float sum=0.0,decay=1.0;
#if LIGHTSHADE_AURORA
    float aspect=LsRenderSize.x/max(LsRenderSize.y,1.0);
#endif
    for(int i=0;i<count;i++){
        vec2 st=clamp(p,vec2(0.001),vec2(0.999));
        // Only open sky lets light through.
#if LIGHTSHADE_AURORA
        // ... and the sky close to the sun lets through most of it: shafts start at the sun, not at the sky.
        vec2 fromSun=(st-source)*vec2(aspect,1.0);
        float open=(texture(Depth,st).r<1e-6?1.0:0.0)*(0.16+1.3*exp(-dot(fromSun,fromSun)*9.0));
        sum+=open*decay;
#else
        sum+=(texture(Depth,st).r<1e-6?1.0:0.0)*decay;
#endif
        decay*=0.94;p+=delta;
    }
    sum/=float(count)*0.55;
    float facing=pow(sat(dot(worldRay(uv),light)*0.5+0.5),6.0);
#if LIGHTSHADE_AURORA
    // Shafts add atmosphere; they never wash the land in yellow. A low sun gets a little more, in its
    // own amber, and the strength is capped where shafts overlap.
    float golden=twilight();
    vec3 tint=sun?sunlightColor()*mix(1.0,1.15,golden):moonlightColor()*0.05*float(LIGHT_NIGHT);
    vec3 shafts=tint*sum*facing*strength*mix(0.11,0.20,golden)*float(GODRAYS_STRENGTH);
    color=vec4(shafts/(1.0+luminance(shafts)*1.5),1.0);
#else
    vec3 tint=sun?sunlightColor()*mix(1.0,1.2,twilight()):moonlightColor()*0.05*float(LIGHT_NIGHT);
    vec3 shafts=tint*sum*facing*strength*0.09*float(GODRAYS_STRENGTH);
    color=vec4(shafts/(1.0+luminance(shafts)*1.5),1.0);
#endif
#endif
}
