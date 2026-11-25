#version 450
#include <sodium:globals.glsl>
#include <sodium:chunk_vertex.glsl>
#include <lightshade:frame.glsl>
#include <lightshade:pack.glsl>
#include <lightshade:waving.glsl>
layout(location=0) out vec4 lsTint;
layout(location=1) out vec2 lsUV;
layout(location=2) out vec3 lsPosition;
layout(location=3) out vec2 lsLight;
layout(location=4) flat out uint lsFlags;
layout(location=5) out float lsFade;
uniform isamplerBuffer u_SectionTimeInfo;
layout(push_constant) uniform LsRegion { vec3 u_RegionOffset; int u_CurrentTime; uint u_RegionID; };
bool lsWater(vec2 uv){for(int i=0;i<3;i++)if(lsInside(uv,LsWaterRect[i]))return true;return false;}
void main(){
    _vert_init();
    uvec3 section=(uvec3(_draw_id)>>uvec3(5,0,2))&uvec3(7,3,7);
    lsPosition=_vert_position+u_RegionOffset+vec3(section)*16.0;
    lsUV=_vert_tex_diffuse_coord+_vert_tex_diffuse_coord_bias*u_TexCoordShrink;
    lsTint=_vert_color;lsLight=_vert_tex_light_coord;lsFlags=_material_params;
    int created=texelFetch(u_SectionTimeInfo,int(u_RegionID*256u+_draw_id)).r;
    lsFade=created<0?1.0:clamp(float(u_CurrentTime-created)*u_FadePeriodInv,0.0,1.0);
    vec3 world=lsPosition+mod(LsCamera.xyz,vec3(32768));
    float range=length(lsPosition);
    // Waving stops at a distance where it can no longer be seen; far chunks
    // keep the plain vertex path.
    if(range<64.0){
        LsMaterial material=lsMaterial(lsUV);
        if(material.kind>0)lsPosition+=lsWave(material,world,LsTime.x)*(1.0-smoothstep(40.0,64.0,range));
    }
#if LIGHTSHADE_AURORA
    // Real waves on the water surface: a low swell shared by neighbouring quads.
    if(float(WATER_WAVES)>0.0&&range<96.0&&lsWater(lsUV)){
        float t=LsTime.x*float(WATER_WAVE_SPEED);
        float swell=sin(dot(world.xz,vec2(0.29,0.17))-t*0.9)*0.6+sin(dot(world.xz,vec2(-0.21,0.37))-t*1.3)*0.4;
        lsPosition.y+=swell*0.030*float(WATER_WAVES)*(1.0-smoothstep(64.0,96.0,range));
    }
#endif
    gl_Position=u_ProjectionMatrix*u_ModelViewMatrix*vec4(lsPosition,1.0);
}
