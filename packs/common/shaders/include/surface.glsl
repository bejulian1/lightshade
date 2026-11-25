#ifndef LS_SURFACE_GLSL
#define LS_SURFACE_GLSL
// Lightshade Aurora's surface layer. Terrain has no normal maps, so relief comes from the texture itself:
// bright texels stand up, dark ones sink. Needs u_BlockTex, lsUV and lsPosition from terrain.fsh.
float texelHeight(ivec2 at,float fallback){
    vec4 t=texelFetch(u_BlockTex,at,0);
    return t.a>0.35?dot(t.rgb,vec3(0.30,0.59,0.11)):fallback;
}
// The face normal tilted by the texture's relief. cavity darkens the low texels between bright ones,
// which is what keeps relief readable in the shade, where no direct light can show it.
vec3 detailNormal(vec3 normal,out float cavity){
    cavity=1.0;
    vec2 texels=lsUV*vec2(textureSize(u_BlockTex,0));
    vec2 dx=dFdx(texels),dy=dFdy(texels);
    // Texels smaller than a pixel only shimmer: relief fades out with distance.
    float strength=float(SURFACE_DETAIL)*(1.0-smoothstep(0.9,2.6,max(length(dx),length(dy))));
    if(strength<=0.001)return normal;
    ivec2 at=ivec2(floor(texels));
    float center=texelHeight(at,0.5);
    float left=texelHeight(at+ivec2(-1,0),center),right=texelHeight(at+ivec2(1,0),center);
    float up=texelHeight(at+ivec2(0,-1),center),down=texelHeight(at+ivec2(0,1),center);
    float around=(left+right+up+down)*0.25;
    // Contrast is relative: dark stone and pale sand should show the same relief.
    float scale=1.0/(around+0.16);
    vec2 slope=vec2(right-left,down-up)*scale;
    vec3 px=dFdx(lsPosition),py=dFdy(lsPosition);
    vec3 along=normalize(px*dy.y-py*dx.y),across=normalize(py*dx.x-px*dy.x);
    cavity=clamp(1.0+(center-around)*scale*1.25*strength,0.52,1.22);
    return normalize(normal-(along*slope.x+across*slope.y)*1.05*strength);
}
#if CLOUD_SHADOWS > 0
// Clouds overhead take the sun off the land: a look along the light through the middle of the layer.
float cloudShadow(vec3 world,vec3 toLight){
    if(toLight.y<0.05||world.y>=CLOUD_BOTTOM)return 1.0;
    float optical=cloudFieldFiltered(world+toLight*((CLOUD_BOTTOM+38.0-world.y)/toLight.y),48.0);
    return mix(1.0,exp(-optical*4.2),smoothstep(0.05,0.18,toLight.y)*0.86);
}
#endif
// Puddles collect on level ground in loose patches of the world; the wetter it is, the wider they spread.
float puddleField(vec3 world,float wetness){
    float field=lsNoise3(vec3(world.xz/54.0,0.37)).a*0.72+lsNoise3(vec3(world.xz/13.0,0.71)).a*0.28;
    return smoothstep(0.545,0.585,field+(wetness-1.0)*0.16);
}
#endif
