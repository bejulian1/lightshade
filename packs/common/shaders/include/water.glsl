// Water optics: needs LsOpaque/LsOpaqueDepth declared by the including shader.
#include <lightshade:noise.glsl>
// The opaque snapshot is display-encoded and point-sampled; four explicit texels
// give smooth linear-light filtering.
vec3 sampleOpaque(vec2 screen){
    ivec2 extent=textureSize(LsOpaque,0),last=extent-1;
    vec2 p=screen*vec2(extent)-0.5,f=fract(p);
    ivec2 i=ivec2(floor(p));
    vec3 a=toLinear(texelFetch(LsOpaque,clamp(i,ivec2(0),last),0).rgb);
    vec3 b=toLinear(texelFetch(LsOpaque,clamp(i+ivec2(1,0),ivec2(0),last),0).rgb);
    vec3 c=toLinear(texelFetch(LsOpaque,clamp(i+ivec2(0,1),ivec2(0),last),0).rgb);
    vec3 d=toLinear(texelFetch(LsOpaque,clamp(i+ivec2(1),ivec2(0),last),0).rgb);
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}

// Screen-space reflection: a short bounded ray, four refinements, one filtered
// colour fetch. The sky is the fallback and confidence fades near the borders.
vec3 reflectedScene(vec3 origin,vec3 direction,vec3 environment){
#if WATER_REFLECTION_QUALITY > 0 && LIGHTSHADE_AURORA
    // Equal steps on the screen, not in the world: a near-mirror reflects far hills at grazing angles,
    // and those are the rays a world-space march runs out of length for.
    const int count=WATER_REFLECTION_QUALITY==1?24:WATER_REFLECTION_QUALITY==2?36:64;
    vec3 start=(LsView*vec4(origin,1.0)).xyz,ray=mat3(LsView)*direction;
    float reach=900.0;
    // Stop short of the camera plane: behind it nothing projects.
    if(ray.z>1e-5)reach=min(reach,max((-0.2-start.z)/ray.z,0.1));
    vec3 finish=start+ray*reach;
    vec4 clipStart=LsProjection*vec4(start,1.0),clipFinish=LsProjection*vec4(finish,1.0);
    if(clipStart.w<=0.0||clipFinish.w<=0.0)return environment;
    float startW=1.0/clipStart.w,finishW=1.0/clipFinish.w;
    vec2 screenStart=clipStart.xy*startW*0.5+0.5,screenFinish=clipFinish.xy*finishW*0.5+0.5,along=screenFinish-screenStart;
    // Clip the line to the screen.
    float limit=1.0;
    if(along.x>1e-6)limit=min(limit,(1.0-screenStart.x)/along.x);else if(along.x<-1e-6)limit=min(limit,-screenStart.x/along.x);
    if(along.y>1e-6)limit=min(limit,(1.0-screenStart.y)/along.y);else if(along.y<-1e-6)limit=min(limit,-screenStart.y/along.y);
    limit=max(limit,0.0);
    float jitter=fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))));
    float previous=0.0,previousGap=-1.0;
    for(int i=0;i<count;i++){
        // Denser near the start, where contact with the shore must not break.
        float f=(float(i)+0.35+0.65*jitter)/float(count);f*=f*limit;
        vec2 screen=screenStart+along*f;
        float z=textureLod(LsOpaqueDepth,screen,0).r;
        float w=mix(startW,finishW,f),t=mix(0.0,reach*finishW,f)/w;
        vec3 p=start+ray*t;
        if(z<=0.000001){previous=f;previousGap=-1.0;continue;}
        float gap=unproject(screen,z).z-p.z;
        if(gap>=0.0&&previousGap<0.0){
            float lo=previous,hi=f;
            for(int j=0;j<5;j++){
                float middle=(lo+hi)*0.5;
                vec2 test=screenStart+along*middle;
                float depth=textureLod(LsOpaqueDepth,test,0).r;
                float tMiddle=mix(0.0,reach*finishW,middle)/mix(startW,finishW,middle);
                float delta=depth>0.000001?unproject(test,depth).z-(start+ray*tMiddle).z:-1.0;
                if(delta>=0.0){hi=middle;screen=test;gap=delta;t=tMiddle;}else lo=middle;
            }
            // A ray that passes behind a silhouette keeps going; thickness grows with the step.
            float stepLength=abs(mix(0.0,reach*finishW,f)/w-mix(0.0,reach*finishW,previous)/mix(startW,finishW,previous));
            float tolerance=0.35+stepLength*1.2;
            if(gap<tolerance){
                float edge=min(min(screen.x,screen.y),min(1.0-screen.x,1.0-screen.y));
                float confidence=smoothstep(0.0,0.035,edge)*(1.0-smoothstep(tolerance*0.6,tolerance,gap));
                vec3 hit=sampleOpaque(screen);
                // The atmosphere pass has not run yet on this snapshot: far hits take their haze here.
                hit=mix(hit,environment,1.0-exp(-t*0.0016*float(ATMOSPHERE)));
                return mix(environment,hit,confidence);
            }
        }
        previousGap=gap;previous=f;
    }
    return environment;
#elif WATER_REFLECTION_QUALITY > 0
    const int count=WATER_REFLECTION_QUALITY==1?12:WATER_REFLECTION_QUALITY==2?20:28;
    vec3 start=(LsView*vec4(origin,1.0)).xyz,ray=mat3(LsView)*direction;
    vec3 eye=opticalEye();
    float previousT=0.12,previousGap=-1.0;
    for(int i=0;i<count;i++){
        float index=float(i+1),t=0.12+0.48*index+0.105*index*index;
        vec3 p=start+ray*t;
        vec4 clip=LsProjection*vec4(p,1.0);
        if(clip.w<=0.0)break;
        vec2 screen=clip.xy/clip.w*0.5+0.5;
        float border=min(min(screen.x,screen.y),min(1.0-screen.x,1.0-screen.y));
        if(border<=0.005)break;
        float z=textureLod(LsOpaqueDepth,screen,0).r;
        if(z<=0.000001){previousT=t;previousGap=-1.0;continue;}
        float gap=length(p-eye)-length(unproject(screen,z)-eye);
        if(gap>=0.0 && previousGap<0.0){
            float lo=previousT,hi=t;
            for(int j=0;j<4;j++){
                float middle=(lo+hi)*0.5;
                vec3 q=start+ray*middle;
                vec4 projected=LsProjection*vec4(q,1.0);
                vec2 test=projected.xy/projected.w*0.5+0.5;
                float depth=textureLod(LsOpaqueDepth,test,0).r;
                float delta=depth>0.000001?length(q-eye)-length(unproject(test,depth)-eye):-1.0;
                if(delta>=0.0){hi=middle;screen=test;gap=delta;}else lo=middle;
            }
            // Reject rays that cross a silhouette gap.
            float tolerance=0.12+hi*0.012;
            float thickness=1.0-smoothstep(tolerance,tolerance*3.0,gap);
            float edge=min(min(screen.x,screen.y),min(1.0-screen.x,1.0-screen.y));
            float confidence=smoothstep(0.015,0.22,edge)*thickness;
            confidence*=1.0-smoothstep(float(count)*0.55,float(count),index);
            float reflectedElevation=LsEnvironment.x>0.5?abs(direction.y):direction.y;
            confidence*=smoothstep(0.02,0.18,reflectedElevation)*0.80;
            return mix(environment,sampleOpaque(screen),confidence);
        }
        previousGap=gap;previousT=t;
    }
#endif
    return environment;
}

// Rain rings on a surface: drops land at random in a grid and each ring runs outward once.
vec2 rainRipples(vec2 p,float time){
    vec2 slope=vec2(0.0);
    for(int layer=0;layer<2;layer++){
        vec2 q=p*(layer==0?1.3:2.1)+float(layer)*17.3,cell=floor(q);
        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
            vec2 id=cell+vec2(x,y);
            vec3 random=fract(vec3(dot(id,vec2(0.1031,0.1137)),dot(id,vec2(0.0973,0.1099)),dot(id,vec2(0.1213,0.0877)))*43.7);
            random=fract(random+dot(random,random.yzx+19.19));
            float life=fract(time*(0.75+random.z*0.5)+random.z*7.0);
            vec2 delta=q-(id+random.xy);
            float dist=length(delta),radius=life*1.1;
            float ring=sin((dist-radius)*16.0)*smoothstep(0.34,0.0,abs(dist-radius))*(1.0-life)*(1.0-life);
            slope+=delta/max(dist,1e-3)*ring;
        }
    }
    return slope*0.16;
}
// Light focused by the waves onto the bottom: soft, slowly drifting webs. Gentle on purpose: a shimmer on
// the sand, not a strobe.
float waterCaustics(vec3 world,vec3 toLight){
    vec2 at=world.xz-toLight.xz/max(toLight.y,0.25)*world.y*0.25;
    float time=LsTime.x;
    float a=1.0-abs(2.0*lsNoise3(vec3(at/8.0+vec2(time*0.010,0.0),time*0.017)).a-1.0);
    float b=1.0-abs(2.0*lsNoise3(vec3(at.yx/12.5+vec2(0.37,time*0.007),0.5+time*0.013)).a-1.0);
    float web=a*b;
    return web*web*web;
}
// One layer of travelling waves: four trains spread around a wind direction, each with its own deep-water
// speed, summed with their exact slopes. Sines, not sharp crests, so the surface stays glassy and the
// reflections bend smoothly instead of breaking into speckle. Trains finer than a pixel fade out.
vec2 waveLayer(vec2 p,float time,float wavelength,float wind,float worldPixel){
    vec2 slope=vec2(0.0);
    for(int i=0;i<4;i++){
        float angle=wind+(float(i)-1.5)*0.62;
        vec2 d=vec2(cos(angle),sin(angle));
        float k=2.0*PI/(wavelength*(1.0-0.17*float(i)));
        float speed=sqrt(9.81*k)*0.30;
        float amplitude=1.0/(1.0+0.75*float(i));
        float keep=1.0-smoothstep(0.30,0.95,worldPixel*k/PI);
        slope+=d*cos(dot(d,p)*k-time*speed+float(i)*1.93)*k*amplitude*keep;
    }
    return slope;
}
// Analytic water slope (dh/dx, dh/dz): a long swell layer and a fine ripple layer moving across it, in
// slow-drifting patches of breeze. Calmer with distance so far water mirrors the sky cleanly.
vec2 waterSlope(vec3 absolutePosition,float range,float worldPixel,float verticalFace){
    float smoothness=clamp(float(WATER_SMOOTHNESS),0.0,1.0),time=LsTime.x*float(WATER_WAVE_SPEED);
    vec2 at=absolutePosition.xz;
    // A gentle warp keeps the trains from reading as a regular grid.
    vec2 warped=at+vec2(sin(at.y*0.23+time*0.31),cos(at.x*0.19-time*0.27))*0.9;
    float breeze=lsNoise3(vec3(at/240.0+vec2(time*0.0021,time*0.0014),time*0.0030)).a;
    breeze=mix(smoothstep(0.30,0.70,breeze),1.0,rainAmount()*0.7);
    float strength=mix(0.40,1.0,breeze);
    vec2 swell=waveLayer(warped,time,7.5,0.55,worldPixel)*0.022*mix(1.15,0.75,smoothness);
    vec2 ripple=waveLayer(warped*1.0+vec2(13.1,7.7),time*1.35,2.3,2.15,worldPixel)*0.0065*mix(1.0,0.30,smoothness);
    vec2 slope=(swell+ripple*strength)*strength/(1.0+range*0.022);
    slope*=1.0-smoothstep(0.6,3.0,worldPixel);
    if(LsRenderSize.w>0.01&&worldPixel<0.4)slope+=rainRipples(at,LsTime.x)*rainAmount()*(1.0-smoothstep(0.1,0.4,worldPixel));
    return slope*float(WATER_WAVES)*verticalFace;
}
