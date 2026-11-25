#ifndef LS_FRAME_GLSL
#define LS_FRAME_GLSL
// Lightshade frame block (1296 bytes, std140), uploaded once per frame by the loader.
// LsTime: seconds, overworld clock, rain, dimension (0 overworld, -1 nether, 1 end, 2 other)
// LsSun/LsMoon: direction (xyz); LsMoon.w is the moon phase
// LsCamera: absolute camera position. LsRenderSize: width, height, opaque snapshot ready,
//                 how wet the land around the camera is (0..1, eased; only where rain really falls)
// LsEnvironment: camera fluid (0 air, 1 water, 2 lava, 3 powder snow), moon phase,
//                 eye sky light 0..1 (smoothed), eye block light 0..1 (smoothed)
// LsLightInfo: number of point lights, radius inside which they replace Minecraft's light levels
// LsLights: light-emitting blocks near the camera: xyz relative to the camera, w = level + 16 * colour
//            class (0 fire, 1 soul fire, 2 redstone, 3 cool white), nearest first
layout(std140) uniform LsFrame {
    mat4 LsProjection; mat4 LsView; mat4 LsInverseProjection; mat4 LsInverseView;
    vec4 LsTime; vec4 LsSun; vec4 LsCamera; vec4 LsRenderSize;
    vec4 LsWaterRect[3]; vec4 LsEmissionRect[16];
    vec4 LsFogColor; vec4 LsFogDistances; vec4 LsHeldPosition[2]; vec4 LsHeldColor[2];
    vec4 LsEnvironment; vec4 LsNativeSky; vec4 LsMoon;
    vec4 LsLightInfo; vec4 LsLights[32];
};
const float LS_PI=3.141592653589793;
vec3 lsLinear(vec3 c){c=max(c,vec3(0));return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
vec3 lsDisplay(vec3 c){c=max(c,vec3(0));return mix(c*12.92,1.055*pow(c,vec3(1.0/2.4))-.055,step(vec3(.0031308),c));}
vec3 lsViewPosition(vec2 p,float z){vec4 q=LsInverseProjection*vec4(p*2.0-1.0,z,1.0);return q.xyz/q.w;}
vec3 lsWorldPosition(vec2 p,float z){return (LsInverseView*vec4(lsViewPosition(p,z),1.0)).xyz;}
bool lsInside(vec2 p,vec4 r){return r.z>r.x&&all(greaterThanEqual(p,r.xy))&&all(lessThanEqual(p,r.zw));}
#endif
