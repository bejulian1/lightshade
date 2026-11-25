#ifndef LS_MATERIALS_GLSL
#define LS_MATERIALS_GLSL
// LsMaterials: one texel per atlas cell, written by the loader from the block
// models. R = kind (0 none, 1 plant, 2 leaves, 3 upper half of a tall plant,
// 4 hanging plant), G = the cell's row inside its sprite, B = the sprite's rows,
// A = light emission of the block the sprite belongs to (0..1).
uniform sampler2D LsMaterials;
struct LsMaterial{int kind;float height;float emission;};
LsMaterial lsMaterial(vec2 atlasUV){
    ivec2 size=textureSize(LsMaterials,0);
    vec2 cell=atlasUV*vec2(size);
    vec4 m=texelFetch(LsMaterials,clamp(ivec2(cell),ivec2(0),size-1),0);
    LsMaterial result;
    result.kind=int(m.r*255.0+0.5);
    float rows=max(m.b*255.0,1.0);
    // 0 at the top edge of the sprite, 1 at its bottom edge.
    float down=(m.g*255.0+fract(cell.y))/rows;
    result.height=result.kind==3?2.0-down:result.kind==4?down:1.0-down;
    result.emission=m.a;
    return result;
}
#endif
