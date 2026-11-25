#ifndef LS_SKY_GLSL
#define LS_SKY_GLSL
#include <lightshade:atmospheric.glsl>
#include <lightshade:clouds.glsl>
vec3 lsSkyBase(vec3 ray,bool discs){
    vec3 color=skyRadiance(ray,discs);
    if(discs)color+=starField(ray,1.0/max(LsRenderSize.y,1.0));
    return color;
}
// The sky seen in water, with the clouds' broad shape.
vec3 lsSkyReflection(vec3 ray,vec3 position,bool discs){
    vec3 sky=lsSkyBase(ray,discs);
#if CLOUD_QUALITY > 0
    if(!isOverworld()||ray.y<0.025)return sky;
#if LIGHTSHADE_AURORA
    if(position.y>=CLOUD_TOP)return sky;
    float near=max(0.0,(CLOUD_BOTTOM-position.y)/ray.y);
    float far=min(4800.0,(CLOUD_TOP-position.y)/ray.y);
    if(far<=near)return sky;
    // Water shows the clouds' soft shapes, not their fine detail: a wide footprint and few samples keep
    // the reflection smooth instead of breaking it into bright blotches.
    const int count=CLOUD_QUALITY==1?4:CLOUD_QUALITY==2?6:9;
    float segment=(far-near)/float(count);
    float optical=0.0,weightedDistance=0.0;
    for(int i=0;i<count;i++){
        float t=near+(float(i)+0.5)*segment;
        float density=cloudFieldFiltered(position+ray*t,max(segment*0.9,max(t*0.07,44.0)));
        optical+=density;weightedDistance+=density*t;
    }
    if(optical<0.001)return sky;
    float distance=weightedDistance/optical;
    vec3 p=position+ray*distance;
    vec3 lightDir=sunDir().y>-0.075?sunDir():moonDir();
    float blocker=(cloudFieldFiltered(p+lightDir*16.0,60.0)+cloudFieldFiltered(p+lightDir*44.0,60.0))*1.4;
    vec3 cloud=cloudLight(cloudFillLight(),cloudKeyLight(),(p.y-CLOUD_BOTTOM)/(CLOUD_TOP-CLOUD_BOTTOM),blocker,dot(ray,lightDir),optical/float(count));
    cloud=mix(sky,cloud,exp(-max(distance-700.0,0.0)*0.00085));
    float opacity=(1.0-exp(-optical*segment*0.045))*smoothstep(0.030,0.20,ray.y)*0.82;
    // Seen in water a cloud is a little darker than the sky's brightest parts: no white patches.
    cloud=min(cloud,vec3(max(luminance(sky)*2.2,0.9)));
    return mix(sky,cloud,opacity);
#else
    if(position.y>=192.0)return sky;
    float t=(192.0-position.y)/ray.y;
    float cover=cloudSheet((position+ray*t).xz)*smoothstep(0.025,0.14,ray.y)*exp(-t*0.00055);
    vec3 cloud=cloudFillLight()*1.35+cloudKeyLight()*0.55;
    return mix(sky,cloud,cover*0.72);
#endif
#else
    return sky;
#endif
}
vec3 lsSky(vec3 ray,bool discs){return lsSkyReflection(ray,stableWorld(cameraOffset()),discs);}
#endif
