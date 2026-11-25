#version 450
// Bloom, second level (Lightshade Aurora): a wide, soft glow at quarter resolution.
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Bloom;
void main(){
    vec2 t=1.0/vec2(textureSize(Bloom,0));vec3 sum=vec3(0);float weights=0.0;
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++){float w=exp(-float(x*x+y*y)*.35);sum+=texture(Bloom,uv+vec2(x,y)*t*1.5).rgb*w;weights+=w;}
    color=vec4(sum/weights,1.0);
}
