#ifndef LS_SHADOW_RECEIVER
#define LS_SHADOW_RECEIVER
#ifndef SHADOW_CASCADES
#define SHADOW_CASCADES 1
#endif
#ifndef SHADOW_SOFTNESS
#define SHADOW_SOFTNESS 1.0
#endif
// Bound by the loader. All cascades look along the same light and share one depth range, so a depth from
// one map means the same as a depth from another. With SHADOWS=0 the maps are never drawn.
layout(std140) uniform LsShadowFrame {
    mat4 LsLightMatrix;      // camera-relative position to the light's clip space (outermost cascade)
    vec4 LsShadowInfo;       // ready, 1/size, radius in blocks, depth range in blocks
    vec4 LsShadowLight;      // unit direction to the light the maps use; w: how much it counts (0 at the horizon)
    vec4 LsShadowCascade[3]; // nearest first: clip scale, clip offset xy, blocks per texel
};
uniform sampler2D LsShadowMap;  // the outermost cascade
uniform sampler2D LsShadowMid;
uniform sampler2D LsShadowNear;

#if SHADOWS > 0
vec4 lsShadowTexels(int cascade,vec2 uv){
#if SHADOW_CASCADES > 2
    if(cascade==1)return textureGather(LsShadowMid,uv);
#endif
#if SHADOW_CASCADES > 1
    if(cascade==0)return textureGather(LsShadowNear,uv);
#endif
    return textureGather(LsShadowMap,uv);
}
// How far behind the depth map each of the four texels around st lies, st in texels. A block face is flat,
// so instead of one depth with a bias against acne the receiver's own plane (z plus slope per texel) is
// taken at each texel's centre. A surface can then never shadow itself, even with the sun low across it.
vec4 lsShadowGap(int cascade,vec2 st,float z,vec2 slope,out vec2 f){
    vec2 corner=floor(st-.5)+1.0;f=st-corner+.5;
    vec4 plane=z+vec4(dot(slope,vec2(-f.x,1.0-f.y)),dot(slope,vec2(1.0-f.x,1.0-f.y)),dot(slope,vec2(1.0-f.x,-f.y)),dot(slope,-f));
    return plane-lsShadowTexels(cascade,corner*LsShadowInfo.y);
}
// One filtered tap: the four compares, blended by where st falls between them.
float lsShadowTap(int cascade,vec2 st,float z,vec2 slope){
    vec2 f;vec4 lit=1.0-step(1e-7,lsShadowGap(cascade,st,z,slope,f));
    return mix(mix(lit.w,lit.z,f.x),mix(lit.x,lit.y,f.x),f.y);
}
#if LIGHTSHADE_AURORA
// Spread over a disc; each pixel turns the disc by its own noise, which hides the taps.
vec2 lsShadowDisc(int index,int count,float turn){
    float angle=float(index)*2.39996323+turn*6.2831853;
    return vec2(cos(angle),sin(angle))*sqrt((float(index)+.5)/float(count));
}
// Penumbra that widens with the distance from the caster: first find how far away the blockers around this
// point are, then filter over the width that distance gives. Sharp at the foot of a wall, soft at a tree top.
float lsShadowSoft(int cascade,vec2 st,float z,vec2 slope,float texel,float noise){
    float range=LsShadowInfo.w,spread=.024*float(SHADOW_SOFTNESS),widest=max(1.1*float(SHADOW_SOFTNESS),3.0*texel);
    float search=widest/texel,found=0.0,distances=0.0;
    const int SEARCH=12;
    for(int i=0;i<SEARCH;i++){
        vec2 offset=lsShadowDisc(i,SEARCH,noise)*search;
        vec2 f;vec4 gap=lsShadowGap(cascade,st+offset,z+dot(slope,offset),slope,f);
        vec4 hit=step(1e-7,gap);
        found+=hit.x+hit.y+hit.z+hit.w;distances+=dot(gap,hit);
    }
    if(found<.5)return 1.0;
    float width=clamp(distances/found*range*spread,1.2*texel,widest)/texel;
    int count=width<3.0?10:28;float lit=0.0;
    for(int i=0;i<count;i++){
        vec2 offset=lsShadowDisc(i,count,noise)*width;
        lit+=lsShadowTap(cascade,st+offset,z+dot(slope,offset),slope);
    }
    return lit/float(count);
}
#else
// Lightshade Daylight: a Poisson disc of 12 filtered taps, turned per pixel by interleaved gradient noise. The
// edge of a shadow becomes a smooth gradient instead of the stair-steps of the map's texels, at a fixed,
// small cost.
const vec2 LS_POISSON[12]=vec2[12](vec2(-0.326,-0.406),vec2(-0.840,-0.074),vec2(-0.696,0.457),vec2(-0.203,0.621),
    vec2(0.962,-0.195),vec2(0.473,-0.480),vec2(0.519,0.767),vec2(0.185,-0.893),vec2(0.507,0.064),vec2(0.896,0.412),
    vec2(-0.322,-0.933),vec2(-0.792,-0.598));
float lsShadowSoft(int cascade,vec2 st,float z,vec2 slope,float texel,float noise){
    float angle=noise*6.2831853;
    mat2 turn=mat2(cos(angle),sin(angle),-sin(angle),cos(angle));
    float radius=1.7*float(SHADOW_SOFTNESS),lit=0.0;
    for(int i=0;i<12;i++){
        vec2 offset=turn*LS_POISSON[i]*radius;
        lit+=lsShadowTap(cascade,st+offset,z+dot(slope,offset),slope);
    }
    return lit/12.0;
}
#endif
#endif

// p: camera-relative position, n: unit normal of the face. thin: a plant or a pane, lit from either side.
float lsShadow(vec3 p,vec3 n,bool thin){
#if SHADOWS > 0
    if(LsShadowInfo.x<.5)return 1.0;
    float weight=LsShadowLight.w;
    if(weight<=0.0)return 1.0;
    float facing=dot(n,LsShadowLight.xyz);
    if(thin){if(facing<0.0){n=-n;facing=-facing;}}
    // A face turned from the light is in its own shadow, at any distance and with no lookup. Faces edge-on
    // to the light blend into that, so a wall does not snap between lit and dark as the sun crosses it.
    float exposed=thin?1.0:smoothstep(.0,.07,facing);
    if(exposed<=0.0)return 1.0-weight;
    // Normal offset: look the point up a little off its face, more for faces at a grazing angle to the
    // light. With the receiver plane below this keeps grass steps and walls free of acne, and the offset
    // is far below a texel of the nearest map, so nothing floats.
    p+=n*(0.012+0.5*LsShadowCascade[SHADOW_CASCADES-1].w*(1.0-facing));

    vec3 q=(LsLightMatrix*vec4(p,1.0)).xyz;
    float radius=LsShadowInfo.z;
    float reach=max(max(abs(q.x),abs(q.y)),length(p.xz)/radius);
    float fade=1.0-smoothstep(.80,.97,reach);
    if(fade<=0.0||q.z<=0.0||q.z>=1.0)return mix(1.0,exposed,weight);

    // Pick the nearest cascade that holds this point with room for the filter, and thin out into the next one.
    float noise=fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))));
    int cascade=SHADOW_CASCADES-1;vec2 clip=q.xy;
#if SHADOW_CASCADES > 1
    for(int i=0;i<SHADOW_CASCADES-1;i++){
        vec2 inner=q.xy*LsShadowCascade[i].x+LsShadowCascade[i].yz;
        float edge=max(abs(inner.x),abs(inner.y));
        if(edge<.92-.14*noise){cascade=i;clip=inner;break;}
    }
#endif
    vec2 st=(clip*.5+.5)/LsShadowInfo.y;
    float texel=LsShadowCascade[cascade].w,range=LsShadowInfo.w;

    // The face's plane in the light's clip space: how fast depth changes per block across the map, kept
    // finite for a face nearly edge-on. Thin plants have no plane worth trusting and get a plain offset.
    vec3 axisX=vec3(LsLightMatrix[0][0],LsLightMatrix[1][0],LsLightMatrix[2][0]);
    vec3 axisY=vec3(LsLightMatrix[0][1],LsLightMatrix[1][1],LsLightMatrix[2][1]);
    vec3 axisZ=vec3(LsLightMatrix[0][2],LsLightMatrix[1][2],LsLightMatrix[2][2]);
    vec3 plane=vec3(dot(axisX,n)/dot(axisX,axisX),dot(axisY,n)/dot(axisY,axisY),dot(axisZ,n)/dot(axisZ,axisZ));
    float steepness=length(plane.xy)/radius/max(abs(plane.z)/range,1e-9);
    vec2 slope=vec2(0);float bias=.012;
    if(thin)bias=.12+texel;
    else slope=-plane.xy/(plane.z+(plane.z<0.0?-1e-6:1e-6))*min(1.0,5.0/max(steepness,1e-4));
    // slope is depth per clip unit of the outermost cascade; one texel of this cascade is texel/radius of those.
    vec2 perTexel=slope*texel/radius;
    float lit=lsShadowSoft(cascade,st,q.z-bias/range,perTexel,texel,noise);
    return mix(1.0,exposed*mix(1.0,lit,fade),weight);
#else
    return 1.0;
#endif
}
#endif
