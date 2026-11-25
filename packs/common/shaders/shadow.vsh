#version 450
// Shadow map vertex program: the loader's own, plus the same wind as terrain.vsh, so the shadows of
// grass, flowers and leaves move with them. The loader's fragment program reads these outputs.
#include <sodium:chunk_vertex.glsl>
#include <lightshade:frame.glsl>
#include <lightshade:pack.glsl>
#include <lightshade:waving.glsl>
layout(std140) uniform LsShadowFrame {mat4 LsLightMatrix;vec4 LsShadowInfo;};
layout(push_constant) uniform ShadowRegion {vec4 ShadowRegionOffset;};
layout(location=0) out vec2 shadowUV;layout(location=1) out float shadowAlpha;layout(location=2) flat out uint shadowFlags;
void main(){
    _vert_init();
    uvec3 section=(uvec3(_draw_id)>>uvec3(5,0,2))&uvec3(7,3,7);
    vec3 p=_vert_position+vec3(section)*16.0+ShadowRegionOffset.xyz;
    float range=length(p);
    // Nudge the corner UV into its own sprite, as Sodium's shrink does for terrain.
    LsMaterial material=lsMaterial(_vert_tex_diffuse_coord+_vert_tex_diffuse_coord_bias*1e-4);
    if(range<64.0&&material.kind>0)p+=lsWave(material,p+mod(LsCamera.xyz,vec3(32768)),LsTime.x)*(1.0-smoothstep(40.0,64.0,range));
    gl_Position=LsLightMatrix*vec4(p,1);
    shadowUV=_vert_tex_diffuse_coord;shadowAlpha=_vert_color.a;shadowFlags=_material_params;
    // A map too coarse to resolve a leaf's holes only aliases them, and they boil as the sun moves. There a
    // leaf block is solid. LsShadowInfo here is this cascade's: 1/size in y, half width in blocks in z.
    if(material.kind==2&&2.0*LsShadowInfo.z*LsShadowInfo.y>.07)shadowFlags&=~6u;
}
