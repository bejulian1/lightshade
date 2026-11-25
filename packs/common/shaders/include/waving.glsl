#ifndef LS_WAVING_GLSL
#define LS_WAVING_GLSL
#include <lightshade:materials.glsl>
// Wind: one slow gust field moving across the land, plus a quicker flutter.
// Positions are absolute (stableWorld), so neighbouring quads that share a
// vertex move together and nothing tears apart.
vec3 lsWind(vec3 world,float time){
    float gust=sin(dot(world.xz,vec2(0.071,0.043))-time*0.9)*0.5+0.5;
    gust=0.35+0.65*gust*gust;
    float sway=sin(time*1.7+dot(world.xz,vec2(0.62,0.37)))+0.45*sin(time*2.9+world.x*1.13-world.z*0.71);
    float across=sin(time*1.3+dot(world.xz,vec2(-0.41,0.83)))*0.5;
    return vec3(sway,0.0,across)*gust;
}
// Displacement in blocks for a terrain vertex of the given material.
vec3 lsWave(LsMaterial material,vec3 world,float time){
    float rain=1.0+LsTime.z*0.8;
    if(material.kind==1||material.kind==3){
        float h=max(material.height,0.0);
        vec3 wind=lsWind(world,time)*rain;
        return vec3(wind.x*0.085,-abs(wind.x)*0.012*h,wind.z*0.06)*h*float(WAVING_PLANTS);
    }
    if(material.kind==2){
        float t=time*1.9+dot(world,vec3(0.71,0.37,0.53));
        vec3 wobble=vec3(sin(t),sin(t*1.31+1.7)*0.6,cos(t*0.87+0.4))*0.022;
        return wobble*(0.6+0.4*lsWind(world,time).x)*rain*float(WAVING_LEAVES);
    }
    if(material.kind==4){
        vec3 wind=lsWind(world,time*0.8)*rain;
        return vec3(wind.x*0.06,0.0,wind.z*0.05)*material.height*float(WAVING_PLANTS);
    }
    return vec3(0.0);
}
#endif
