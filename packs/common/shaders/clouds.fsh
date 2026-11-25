#version 450
#include <lightshade:sky.glsl>
// Clouds at half resolution: premultiplied radiance and coverage.
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 cloudRadiance;
uniform sampler2D Depth;
void main(){
    cloudRadiance=vec4(0);
#if CLOUD_QUALITY > 0
    if(!isOverworld()||LsEnvironment.x>0.5)return;
    vec3 rd=worldRay(texCoord),eye=stableWorld(cameraOffset());
    float depth=texture(Depth,texCoord).r;
    float surface=depth>1e-6?length(unproject(texCoord,depth)-opticalEye()):1e9;
#if LIGHTSHADE_AURORA
    float angularWidth=max(length(dFdx(rd)),length(dFdy(rd)));
    float inside=smoothstep(CLOUD_BOTTOM,CLOUD_BOTTOM+3.0,eye.y)*(1.0-smoothstep(CLOUD_TOP-3.0,CLOUD_TOP,eye.y));
    if(abs(rd.y)<0.012 && inside<0.001)return;
    float invY=1.0/(abs(rd.y)>1e-5?rd.y:1e-5);
    float a=(CLOUD_BOTTOM-eye.y)*invY,b=(CLOUD_TOP-eye.y)*invY;
    float near=max(0.0,min(a,b)),far=min(min(mix(5200.0,900.0,inside),max(a,b)),surface);
    if(far<=near)return;
    // Steps grow with distance, are longer through empty air, and start at a per-pixel offset
    // that the edge-aware upsample smooths away.
    const int count=CLOUD_QUALITY==1?36:CLOUD_QUALITY==2?64:110;
    const float nearStep=CLOUD_QUALITY==1?7.0:CLOUD_QUALITY==2?4.5:3.0;
    const float spread=CLOUD_QUALITY==1?0.030:CLOUD_QUALITY==2?0.018:0.011;
    vec3 lightDir=sunDir().y>-0.075?sunDir():moonDir();
    vec3 fill=cloudFillLight(),key=cloudKeyLight(),atmosphere=skyFogColor(rd);
    float around=dot(rd,lightDir);
    vec3 result=vec3(0);float trans=1.0;
    float t=near+max(nearStep,near*spread)*0.7*fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))));
    for(int i=0;i<count&&t<far;i++){
        float step=max(nearStep,t*spread);
        vec3 p=eye+rd*(t+step*0.5);
        float density=cloudFieldFiltered(p,max(step*0.55,t*angularWidth));
        if(density<0.002){t+=step*1.6;continue;}
        float opacity=1.0-exp(-density*step*0.085);
        float blocker=cloudFieldFiltered(p+lightDir*9.0,60.0)*0.9+cloudFieldFiltered(p+lightDir*24.0,60.0)*1.1;
#if CLOUD_QUALITY > 1
        blocker+=cloudFieldFiltered(p+lightDir*48.0,60.0)*1.1;
#endif
#if CLOUD_QUALITY > 2
        blocker+=cloudFieldFiltered(p+lightDir*90.0,60.0)*1.0;
#endif
        vec3 radiance=cloudLight(fill,key,(p.y-CLOUD_BOTTOM)/(CLOUD_TOP-CLOUD_BOTTOM),blocker,around,density);
        radiance=mix(atmosphere,radiance,exp(-max(t-700.0,0.0)*0.00085));
        // A cloud next to a low sun stays below the bloom: the sun stays a disc, not a white mass.
        float peak=max(radiance.r,max(radiance.g,radiance.b));
        radiance*=mix(1.0,min(1.0,1.12/max(peak,1e-3)),goldenHour());
        result+=trans*opacity*radiance;
        trans*=1.0-opacity;
        if(trans<0.012)break;
        t+=step;
    }
    float horizon=mix(smoothstep(0.012,0.055,abs(rd.y)),1.0,inside);
    cloudRadiance=vec4(result,1.0-trans)*horizon;
#else
    // Lightshade Daylight: one flat layer at Minecraft's cloud height, lit from above
    // and a little darker in its thick middle.
    if(abs(rd.y)<0.01)return;
    float t=(192.0-eye.y)/rd.y;
    if(t<=0.0||t>surface)return;
    vec3 p=eye+rd*t;
    float core;
    float cover=cloudSheet(p.xz,core);
    if(cover<0.002)return;
    // Light from the sun's side: a point with more cloud toward the sun is in the cloud's own shadow.
    float toward;cloudSheet(p.xz+sunDir().xz*10.0,toward);
    float fine=volumeNoise(vec3(p.xz/9.0+vec2(LsTime.x*0.03,0.0),11.5));
    float shade=clamp(1.0-0.55*toward-0.25*core+0.12*(fine-0.5),0.25,1.0);
    vec3 key=cloudKeyLight()*(1.0-cloudTwilight()*0.45);
    vec3 lit=cloudFillLight()*mix(1.75,1.25,core)+key*0.62*shade;
    // A thin bright rim when the sun is low behind the edges.
    lit+=key*(1.0-core)*pow(sat(dot(rd,sunDir())),6.0)*0.6;
    lit=mix(skyFogColor(rd),lit,exp(-max(t-400.0,0.0)*0.0009));
    float opacity=cover*mix(0.78,0.95,core)*smoothstep(0.01,0.06,abs(rd.y));
    cloudRadiance=vec4(lit*opacity,opacity);
#endif
#endif
}
