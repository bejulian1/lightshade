#version 450
// Bloom, third level (Lightshade Aurora): a broad glow at an eighth of the resolution.
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Bloom;
void main(){
    vec2 t=1.0/vec2(textureSize(Bloom,0));vec3 sum=vec3(0);float weights=0.0;
    for(int y=-3;y<=3;y++)for(int x=-3;x<=3;x++){float w=exp(-float(x*x+y*y)*.16);sum+=texture(Bloom,uv+vec2(x,y)*t*1.5).rgb*w;weights+=w;}
    color=vec4(sum/weights,1.0);
}
