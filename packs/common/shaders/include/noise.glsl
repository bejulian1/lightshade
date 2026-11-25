#ifndef LS_NOISE_GLSL
#define LS_NOISE_GLSL
// Tiling 3D noise, kept by the loader as a 2D atlas of 64 slices (NoiseVolume.java): 8x8 tiles of 64x64
// with a wrapped one-texel gutter. Two bilinear fetches. The period is 1 on every axis.
// R Perlin-Worley, G Worley (6 cells), B Worley (10 cells), A Perlin (5 cells).
uniform sampler2D LsNoise;
vec4 lsNoise3(vec3 p){
    p=fract(p);
    float z=p.z*64.0,slice=floor(z),blend=z-slice,next=mod(slice+1.0,64.0);
    vec2 inside=p.xy*62.0+1.5;
    vec2 a=vec2(mod(slice,8.0),floor(slice/8.0))*64.0+inside;
    vec2 b=vec2(mod(next,8.0),floor(next/8.0))*64.0+inside;
    return mix(textureLod(LsNoise,a/512.0,0.0),textureLod(LsNoise,b/512.0,0.0),blend);
}
#endif
