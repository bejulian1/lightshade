#version 450
// Bloom, first level: bright parts of the linear scene, filtered down to half
// resolution with a 13-tap kernel (no flicker from single bright pixels).
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Scene;
// The world is drawn in 8-bit, so a flame and white snow both end at 1.0. Glowing blocks are marked
// by terrain.fsh (atmosphere alpha); only they, and anything brighter than 1.0 (the sun), glow.
vec3 bright(vec2 st){
    vec4 c=texture(Scene,st);float m=max(c.r,max(c.g,c.b));
    float knee=c.a*smoothstep(.25,.85,m)+smoothstep(1.0,1.6,m);
    return c.rgb*min(knee,1.0);
}
void main(){
    vec2 t=1.0/vec2(textureSize(Scene,0));
    vec3 a=bright(uv+t*vec2(-2,-2)),b=bright(uv+t*vec2(0,-2)),c=bright(uv+t*vec2(2,-2));
    vec3 d=bright(uv+t*vec2(-1,-1)),e=bright(uv+t*vec2(1,-1));
    vec3 f=bright(uv+t*vec2(-2,0)),g=bright(uv),h=bright(uv+t*vec2(2,0));
    vec3 i=bright(uv+t*vec2(-1,1)),j=bright(uv+t*vec2(1,1));
    vec3 k=bright(uv+t*vec2(-2,2)),l=bright(uv+t*vec2(0,2)),m=bright(uv+t*vec2(2,2));
    vec3 sum=(d+e+i+j)*0.125+(a+c+k+m)*0.03125+(b+f+h+l)*0.0625+g*0.125;
    color=vec4(sum,1.0);
}
