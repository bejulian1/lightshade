#ifndef LIGHTSHADE_CLOUD_FIELD
#define LIGHTSHADE_CLOUD_FIELD
#include <lightshade:atmospheric.glsl>
#ifndef CLOUD_SILVER_LINING
#define CLOUD_SILVER_LINING 0.5
#endif
// Integer lattice corners make identical shared corners exact. Signed cell
// coordinates convert modulo 2^32; only the upper 24 bits become floating point.
float cloudLatticeHash(ivec3 cell){
    uvec3 c=uvec3(cell);
    uint h=(c.x*0x6c8e9cf5u)^(c.y*0xb5297a4du)^(c.z*0x1b56c4e9u);
    h^=h>>13u;h*=0x68e31da5u;h^=h>>17u;h*=0x7feb352du;h^=h>>15u;
    return float(h>>8u)*(1.0/16777216.0);
}
float volumeNoise(vec3 p){
    ivec3 i=ivec3(floor(p));vec3 f=fract(p);f=f*f*(3.0-2.0*f);
    return mix(mix(mix(cloudLatticeHash(i),cloudLatticeHash(i+ivec3(1,0,0)),f.x),mix(cloudLatticeHash(i+ivec3(0,1,0)),cloudLatticeHash(i+ivec3(1,1,0)),f.x),f.y),mix(mix(cloudLatticeHash(i+ivec3(0,0,1)),cloudLatticeHash(i+ivec3(1,0,1)),f.x),mix(cloudLatticeHash(i+ivec3(0,1,1)),cloudLatticeHash(i+ivec3(1,1,1)),f.x),f.y),f.z);
}
#if LIGHTSHADE_AURORA
#include <lightshade:noise.glsl>
const float CLOUD_BOTTOM=184.0,CLOUD_TOP=276.0;
// One continuous field for the visible volume, its water reflection and its shadow on the land.
// Perlin-Worley gives the soft body; detail finer than the integration footprint fades out instead
// of turning into bands.
float cloudFieldFiltered(vec3 p,float footprint){
    float height=(p.y-CLOUD_BOTTOM)/(CLOUD_TOP-CLOUD_BOTTOM);
    if(height<=0.0||height>=1.0)return 0.0;
    vec3 q=p+vec3(LsTime.x*0.30,0.0,LsTime.x*0.12);
    q.y+=height*14.0;
    vec4 low=lsNoise3(q/vec3(640.0,450.0,640.0));
    // Clear weather has separate puffs in loose groups; rain joins them into an overcast layer.
    float threshold=0.560-float(CLOUD_COVER)*0.055-rainAmount()*0.20+(low.a-0.5)*0.20+height*height*0.14;
    float top=0.48+0.52*low.g;
    float envelope=smoothstep(0.0,0.17,height)*(1.0-smoothstep(top*0.5,top,height));
    float body=(low.r-threshold)*4.4*envelope;
    if(body<=0.0)return 0.0;
    float keep=1.0-smoothstep(9.0,42.0,footprint);
    if(keep>0.0){
        vec4 mid=lsNoise3(q/vec3(124.0,108.0,124.0));
        // Billows on the crowns, wisps at the base.
        float erosion=mix(1.0-mid.g,mid.g,sat(height*2.6))*0.40+(1.0-mid.b)*0.18;
        body-=erosion*keep;
#if CLOUD_QUALITY > 1
        if(body>0.0&&footprint<16.0){
            float fine=lsNoise3(q/vec3(38.0,34.0,38.0)).b;
            body-=(1.0-fine)*0.10*(1.0-smoothstep(5.0,16.0,footprint));
        }
#endif
    }
    return sat(body*2.9);
}
#endif
// Lightshade Daylight's flat cloud layer: one 2D field at the vanilla cloud height. core is how far inside
// the cloud a point is (0 at the edge, 1 deep inside): thick middles are shaded darker.
float cloudSheet(vec2 xz,out float core){
    vec2 q=xz/64.0+vec2(LsTime.x*0.006,LsTime.x*0.002);
    float n=volumeNoise(vec3(q,0.5))*0.62+volumeNoise(vec3(q*2.7,3.5))*0.28+volumeNoise(vec3(q*7.1,7.5))*0.10;
    float threshold=0.60-float(CLOUD_COVER)*0.05-rainAmount()*0.16;
    core=smoothstep(threshold+0.06,threshold+0.24,n);
    return smoothstep(threshold,threshold+0.08,n);
}
float cloudSheet(vec2 xz){float core;return cloudSheet(xz,core);}
float cloudTwilight(){return twilight();}
// Light from the sky around the clouds: what their shaded undersides show. Sky blue by day, a cool
// blue-lavender at dusk, deep blue at night. Never a flat grey, except under rain.
vec3 cloudFillLight(){
    float day=daylight(),dusk=twilight();
    vec3 fill=mix(vec3(0.010,0.016,0.032)*float(LIGHT_NIGHT),vec3(0.30,0.40,0.58),day);
    fill=mix(fill,vec3(0.26,0.25,0.38)*smoothstep(-0.16,0.04,sunDir().y),dusk*0.85);
    vec3 overcast=vec3(luminance(fill))*vec3(0.95,0.98,1.04);
    return mix(fill,overcast,rainAmount()*0.8)*(1.0-rainAmount()*0.30);
}
// Sunlight on the clouds: warm white by day, apricot and gold when the sun is low. The tops keep the last
// light a little after sunset on the ground.
vec3 cloudKeyLight(){
    float sun=smoothstep(-0.075,0.025,sunDir().y);
    float moon=smoothstep(0.0,0.14,moonDir().y);
    vec3 solar=mix(vec3(1.62,1.52,1.38),vec3(1.70,1.12,0.70),twilight());
    solar=mix(solar,vec3(1.50,0.78,0.46),twilight()*smoothstep(0.10,-0.02,sunDir().y));
    return (solar*sun+vec3(0.040,0.062,0.10)*moon*float(LIGHT_NIGHT))*(1.0-rainAmount()*0.76);
}
#if LIGHTSHADE_AURORA
// height 0 at the base, 1 at the crown. blocker: summed density toward the light. around: dot(view, light).
vec3 cloudLight(vec3 fill,vec3 key,float height,float blocker,float around,float density){
    float direct=exp(-blocker*1.5);
    // Light scattered many times inside a cloud keeps its body bright and soft.
    float soft=mix(0.30,0.52,twilight())*exp(-blocker*0.32);
    float light=max(direct,soft);
    // Forward glow toward the sun (silver lining), capped: a cloud by the sun gets a bright rim, not a
    // white mass.
    float g=0.72,denominator=1.0+g*g-2.0*g*around;
    float forward=0.07*(1.0-g*g)/(denominator*sqrt(max(denominator,0.0004)));
    float phase=0.78+min(forward*float(CLOUD_SILVER_LINING)*1.6,mix(2.2,1.0,twilight()));
    // Thin edges facing away from the light catch less of it (the dark "powder" edge of real cumulus).
    float powder=1.0-0.40*exp(-density*5.0)*sat(0.5-0.5*around);
    // Bases sit in the cloud's own shade and see the sky below: cool and darker. Crowns see the open sky.
    float base=mix(0.62,1.12,smoothstep(0.0,0.75,height));
    return fill*base*(0.84+0.16*exp(-blocker*0.25))+key*light*phase*powder;
}
#endif
#endif
