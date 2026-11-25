#version 450
// Bloom, last level (Lightshade Aurora): the veil of light a very bright source lays over a large part of
// the picture. Same size as the level before it, with a much wider kernel; cheap at this size.
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Bloom;
void main(){
    vec2 t=1.0/vec2(textureSize(Bloom,0));vec3 sum=vec3(0);float weights=0.0;
    for(int y=-6;y<=6;y++)for(int x=-6;x<=6;x++){float w=exp(-float(x*x+y*y)*.045);sum+=texture(Bloom,uv+vec2(x,y)*t*3.2).rgb*w;weights+=w;}
    color=vec4(sum/weights,1.0);
}
