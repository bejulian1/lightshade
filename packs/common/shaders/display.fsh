#version 450
#include <lightshade:atmospheric.glsl>
// Final image: exposure, bloom and godrays, a filmic shoulder, then the grade.
// The grade is where the look is decided: deep blacks, rich but not neon
// colour, and no grey veil over the picture.
layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
uniform sampler2D Scene;uniform sampler2D Bloom;uniform sampler2D Rays;
#if LIGHTSHADE_AURORA
uniform sampler2D BloomWide;uniform sampler2D BloomFar;uniform sampler2D BloomVeil;
#endif
// Soft shoulder on the brightest channel: keeps the hue of a bright sky or
// flame instead of bleaching it to white.
vec3 shoulder(vec3 c){
    const float knee=.72;
    float peak=max(c.r,max(c.g,c.b));
    if(peak<=knee)return c;
    float mapped=knee+(1.0-knee)*(1.0-exp(-(peak-knee)/(1.0-knee)));
    return c*(mapped/peak);
}
void main(){
    vec3 c=texture(Scene,uv).rgb*float(EXPOSURE);
    vec3 glow=texture(Bloom,uv).rgb;
#if LIGHTSHADE_AURORA
    // Weights that sum to one: a wide source such as a lava sea keeps its own brightness, while a tiny
    // very hot one such as the sun spreads into a large glow.
    // The widest glow of a low sun is scattered through more air, so it is the warmest.
    // The widest level is kept small: it is what turns a bright sun into a veil over the whole picture.
    glow=glow*.30+texture(BloomWide,uv).rgb*.32+texture(BloomFar,uv).rgb*.24+texture(BloomVeil,uv).rgb*.14;
#endif
    c+=glow*float(BLOOM_STRENGTH)+texture(Rays,uv).rgb;
#if LIGHTSHADE_AURORA
    // A very bright source burns out to white in its core; lesser highlights keep their colour.
    float burn=max(c.r,max(c.g,c.b));
    c=mix(c,vec3(burn),smoothstep(3.5,13.0,burn));
#endif
    c=shoulder(c);
    // Vibrance lifts muted colours more than already strong ones; saturation
    // then sets the overall richness.
    float l=luminance(c),peak=max(c.r,max(c.g,c.b)),chroma=peak>1e-5?(peak-min(c.r,min(c.g,c.b)))/peak:0.0;
    c=mix(vec3(l),c,float(SATURATION)*(1.0+float(VIBRANCE)*(1.0-chroma)));
    c=max(c,vec3(0));
    vec3 d=lsDisplay(min(c,vec3(1)));
    // Gentle S-curve in display space: darker shadows, brighter highlights.
    d=mix(d,d*d*(3.0-2.0*d),float(CONTRAST));
#if LIGHTSHADE_AURORA
    vec2 q=uv-.5;d*=1.0-float(VIGNETTE)*dot(q,q)*1.6;
#endif
    // Blue-noise-like dither hides banding in dark skies.
    float n=fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))));
    d+=(n-.5)/255.0;
    color=vec4(clamp(d,0.0,1.0),1.0);
}
