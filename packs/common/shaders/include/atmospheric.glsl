// Lightshade: shared radiometry, camera reconstruction and the analytic sky. Everything here is linear light.
//
// The look in one paragraph: a warm, slightly creamy sun (#FFF4DC) and a cool sky-blue fill (#7FA8D8) in
// the shade, so shaded faces keep depth and colour instead of going flat grey-green. The sky follows
// Rayleigh (blue that deepens overhead, paler along the horizon where the light crosses more air) and
// Mie (a soft bright glow around the sun). At sunrise and sunset a golden-amber band hugs the horizon
// under the sun, softens through coral-apricot and fades into cobalt-indigo overhead. Nothing goes neon:
// no magenta dome, and the sun's glow stays a halo instead of a yellow veil over the land.
#ifndef LIGHTSHADE_COMMON
#define LIGHTSHADE_COMMON
#include <lightshade:frame.glsl>
#include <lightshade:pack.glsl>
#ifndef SKY_GOLDEN
#define SKY_GOLDEN 0.0
#endif
#ifndef SUN_GLOW
#define SUN_GLOW 1.0
#endif
#ifndef SKY_FILL
#define SKY_FILL 1.0
#endif
#ifndef CLOUD_SHADOWS
#define CLOUD_SHADOWS 0
#endif
#ifndef SURFACE_DETAIL
#define SURFACE_DETAIL 0.0
#endif
#ifndef FOLIAGE_GLOW
#define FOLIAGE_GLOW 0.0
#endif
#ifndef SKY_MILKY_WAY
#define SKY_MILKY_WAY 0.0
#endif
#ifndef SKY_SHOOTING_STARS
#define SKY_SHOOTING_STARS 0.0
#endif
#ifndef RAIN_PUDDLES
#define RAIN_PUDDLES 0
#endif
#ifndef WATER_FOAM
#define WATER_FOAM 0.0
#endif
#ifndef WATER_CAUSTICS
#define WATER_CAUSTICS 0.0
#endif
const float PI=3.141592653589793;
float sat(float x){return clamp(x,0.0,1.0);}
float luminance(vec3 c){return dot(c,vec3(0.2126,0.7152,0.0722));}
vec3 toLinear(vec3 c){return lsLinear(c);}
vec3 toDisplay(vec3 c){return lsDisplay(c);}
bool isOverworld(){return abs(LsTime.w)<0.5;}
bool isNether(){return abs(LsTime.w+1.0)<0.5;}
bool isEnd(){return abs(LsTime.w-1.0)<0.5;}
vec3 sunDir(){return normalize(LsSun.xyz+vec3(0,1e-7,0));}
vec3 moonDir(){return normalize(LsMoon.xyz+vec3(0,1e-7,0));}
float daylight(){return smoothstep(-0.14,0.16,sunDir().y);}
float rainAmount(){return sat(LsTime.z);}
// Smoothed sky light at the eye: 1 outdoors, 0 deep underground.
float eyeSky(){return sat(LsEnvironment.z);}

// 1 from just below the horizon to a hand above it: the window of sunrise and sunset colour.
float twilight(){
    float a=sunDir().y;
    return smoothstep(-0.20,-0.025,a)*(1.0-smoothstep(0.10,0.40,a));
}
// How much sunset colour this pack paints: the twilight window times the player's setting.
float goldenHour(){return twilight()*sat(float(SKY_GOLDEN));}

// Torch and lamp colour: warm, but a fire rather than an orange filter.
vec3 lampColor(){return vec3(1.0,0.62,0.32);}
// The sun: #FFF4DC by day (linear 1.0, .905, .716), a golden amber when it is low.
vec3 sunlightColor(){
    return mix(vec3(1.0,0.60,0.30),vec3(1.0,0.905,0.716),smoothstep(0.02,0.40,sunDir().y));
}
vec3 moonlightColor(){return vec3(0.56,0.70,1.0);}
// The sky's own light, #7FA8D8 by day (linear .212, .392, .686), calmed a little toward grey so stone in
// the shade reads cool rather than blue.
vec3 skyFillColor(){return vec3(0.25,0.37,0.58);}

// Direct light from the sun or the moon, before shadows. A low sun crosses more air and loses strength.
vec3 keyLight(){
    float a=smoothstep(-0.015,0.12,sunDir().y),b=smoothstep(0.0,0.14,moonDir().y)*(1.0-a);
    float air=mix(0.62,1.0,smoothstep(0.0,0.32,sunDir().y));
#if LIGHTSHADE_AURORA
    vec3 sun=sunlightColor()*air,moon=moonlightColor()*0.070;
#else
    vec3 sun=sunlightColor()*air*0.86,moon=moonlightColor()*0.062;
#endif
    return (sun*a*float(LIGHT_DAY)+moon*b*float(LIGHT_NIGHT))*(1.0-rainAmount()*0.72);
}
// Light from the whole sky, on surfaces that see it. Cool blue by day, blue-lavender at dusk, deep blue
// at night. Under rain the sky is a grey dome that lights everything evenly.
vec3 ambientLight(){
    vec3 day=mix(skyFillColor(),vec3(0.23,0.25,0.37),twilight())*float(SKY_FILL);
    vec3 night=vec3(0.009,0.013,0.026);
    vec3 fill=mix(night*float(LIGHT_NIGHT),day*float(LIGHT_DAY),daylight());
    vec3 overcast=vec3(luminance(fill))*vec3(0.93,0.97,1.04)*1.08;
    return mix(fill,overcast,rainAmount()*0.75)*mix(1.0,0.82,rainAmount());
}
// Clear turquoise by day, near black at night; follows how much sky the eye sees.
vec3 waterFogColor(){
    float night=isOverworld()?float(LIGHT_NIGHT):1.0;
    return vec3(0.006,0.066,0.088)*mix(0.12*night,1.0,daylight())*mix(0.35,1.0,eyeSky());
}

// Vulkan camera depth is reversed-Z; 0 is the sky.
vec3 unproject(vec2 uv,float z){vec4 p=LsInverseProjection*vec4(uv*2.0-1.0,z,1);return p.xyz/p.w;}
vec3 opticalEye(){vec4 p=LsInverseProjection*vec4(0,0,1,0);return abs(p.w)>1e-7?p.xyz/p.w:vec3(0);}
vec3 cameraOffset(){return (LsInverseView*vec4(opticalEye(),1)).xyz;}
vec3 worldPoint(vec2 uv,float z){return (LsInverseView*vec4(unproject(uv,z),1)).xyz;}
vec3 worldRay(vec2 uv){return normalize(mat3(LsInverseView)*(unproject(uv,0.00001)-opticalEye()));}
vec3 stableWorld(vec3 p){return p+mod(LsCamera.xyz,vec3(32768));}
float hashValue(vec3 p){p=fract(p*vec3(0.1031,0.1099,0.0973));p+=dot(p,p.zxy+19.19);return fract((p.x+p.y)*p.z);}
float smoothNoise(vec3 p){
    vec3 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);
    return mix(mix(mix(hashValue(i),hashValue(i+vec3(1,0,0)),f.x),mix(hashValue(i+vec3(0,1,0)),hashValue(i+vec3(1,1,0)),f.x),f.y),mix(mix(hashValue(i+vec3(0,0,1)),hashValue(i+vec3(1,0,1)),f.x),mix(hashValue(i+vec3(0,1,1)),hashValue(i+vec3(1,1,1)),f.x),f.y),f.z);
}

// Northern lights: curtains of emerald with teal tops and a faint violet hem, hanging low in the
// northern sky (-Z in Minecraft) on clear nights. The folds drift and the rays shimmer slowly.
vec3 aurora(vec3 ray){
    float north=smoothstep(0.02,0.35,-ray.z);
    float clear=(1.0-daylight())*(1.0-rainAmount())*smoothstep(0.04,0.16,ray.y)*north;
    if(clear<=0.001)return vec3(0);
    vec3 sum=vec3(0);
    for(int layer=0;layer<2;layer++){
        float height=layer==0?17000.0:21000.0;
        vec3 p=ray*(height/max(ray.y+0.18,0.10));
        float u=p.x*0.00011+LsTime.x*(layer==0?0.0040:-0.0028)+float(layer)*3.1;
        float base=0.42+0.10*sin(u*1.7+float(layer))+0.05*sin(u*4.3-LsTime.x*0.006);
        float h=ray.y*(layer==0?1.0:0.86)-base*0.55;
        float curtain=smoothstep(-0.035,0.0,h)*exp(-max(h,0.0)*(layer==0?9.0:12.0));
        float rays=0.55+0.45*pow(0.5+0.5*sin(u*23.0+sin(u*5.0+LsTime.x*0.05)*2.4),2.0);
        float shimmer=0.85+0.15*sin(LsTime.x*0.7+u*40.0);
        vec3 tint=mix(vec3(0.015,0.20,0.085),vec3(0.010,0.13,0.15),sat(h*14.0));
        sum+=tint*curtain*rays*shimmer*(layer==0?1.0:0.55);
        sum+=vec3(0.035,0.006,0.050)*exp(-abs(h+0.012)*70.0)*0.35;
    }
    return sum*clear*0.55;
}

vec3 skyRadiance(vec3 ray,bool celestial){
    if(isNether()){
        vec3 biome=toLinear(LsFogColor.rgb);
#if LIGHTSHADE_AURORA
        // The biome's own hue pulled toward crimson ember: bright low down where the lava lights the smoke,
        // near black overhead.
        float low=1.0-smoothstep(-0.55,0.45,ray.y);
        vec3 hue=mix(biome,vec3(0.30,0.045,0.020),0.55);
        return hue*(0.10+0.60*low*low);
#else
        return mix(biome*0.30,vec3(0.070,0.008,0.003),0.52+0.20*pow(1.0-abs(ray.y),3.0));
#endif
    }
    if(isEnd()){
#if LIGHTSHADE_AURORA
        // Deep violet-indigo nebula drifting around a band of light, over black space.
        float wind=LsTime.x*0.0015;
        float cloud=smoothNoise(ray*2.3+vec3(9.0,wind,0.0))*0.55+smoothNoise(ray*5.1+vec3(3.0,0.0,wind))*0.30+smoothNoise(ray*11.0+vec3(1.0))*0.15;
        float cool=smoothNoise(ray*3.1+vec3(21.0,4.0,wind));
        float band=exp(-pow(ray.y+0.10+0.18*sin(ray.x*2.4-ray.z*1.7),2.0)*5.0);
        float bright=smoothstep(0.38,0.74,cloud);
        vec3 nebula=mix(vec3(0.070,0.024,0.16),vec3(0.030,0.040,0.16),smoothstep(0.42,0.70,cool));
        return vec3(0.0040,0.0022,0.010)+nebula*bright*(0.17+0.60*band)+vec3(0.12,0.07,0.22)*pow(max(dot(ray,normalize(vec3(0.5,0.42,-0.45))),0.0),24.0)*0.22;
#else
        float arc=ray.y+0.16+0.16*sin(ray.x*3.0-ray.z*2.0);
        float ribbon=exp(-arc*arc*65.0);
        float billow=smoothNoise(ray*5.0+vec3(9,0,LsTime.x*0.001));
        return vec3(0.003,0.0015,0.009)+ribbon*mix(vec3(0.030,0.010,0.064),vec3(0.012,0.020,0.064),billow)*0.72;
#endif
    }
    if(!isOverworld())return toLinear(mix(LsFogColor.rgb,LsNativeSky.rgb,sat(ray.y)));

    float day=daylight(),altitude=sunDir().y,r=rainAmount();
    float h=max(ray.y,0.0);
    float cosSun=dot(ray,sunDir());
    // Rayleigh: a deep blue zenith over a paler horizon.
#if LIGHTSHADE_AURORA
    vec3 zenithDay=vec3(0.070,0.190,0.500),horizonDay=vec3(0.440,0.600,0.780);
#else
    // Daylight keeps the biome's own sky colour, refined toward the same gradient.
    vec3 biome=toLinear(LsNativeSky.rgb);
    vec3 zenithDay=mix(biome*vec3(0.55,0.70,0.98),vec3(0.075,0.200,0.510),0.40);
    vec3 horizonDay=mix(biome*1.10+vec3(0.03),vec3(0.460,0.610,0.780),0.55);
#endif
    vec3 zenithNight=vec3(0.0009,0.0017,0.0050)*float(LIGHT_NIGHT),horizonNight=vec3(0.0055,0.0095,0.021)*float(LIGHT_NIGHT);
    vec3 zenith=mix(zenithNight,zenithDay,day),horizon=mix(horizonNight,horizonDay,day);
    vec3 c=mix(horizon,zenith,pow(h,0.52));
    // Mie by day: the sky brightens and whitens toward the sun.
    c+=vec3(0.30,0.29,0.25)*pow(sat(cosSun*0.5+0.5),8.0)*0.22*day*(1.0-0.5*h);

    float tw=twilight()*sat(float(SKY_GOLDEN));
    if(tw>0.0){
        float sunward=pow(sat(cosSun*0.5+0.5),2.2);
        float low=exp(-h*7.5),mid=exp(-h*4.2);
        // Everything dims as the sun goes under; the colours stay, the light goes.
        float lit=smoothstep(-0.20,0.05,altitude);
        vec3 overhead=mix(vec3(0.008,0.014,0.055),vec3(0.045,0.085,0.300),lit);
        vec3 apricot=vec3(0.92,0.52,0.36)*lit;
        vec3 amber=vec3(1.00,0.56,0.21)*lit;
        // Opposite the sun: the cool band of the Earth's shadow, a soft blue, never pink.
        vec3 antisolar=vec3(0.13,0.16,0.31)*lit;
        vec3 dusk=mix(overhead,mix(antisolar,apricot,sunward),mid*0.80);
        dusk=mix(dusk,mix(antisolar*1.25,amber,sunward),low*mix(0.30,1.0,sunward));
        c=mix(c,dusk,tw);
    }
    // Overcast: a grey dome, a little brighter toward the horizon.
    c=mix(c,vec3(luminance(c))*vec3(0.88,0.94,1.04)*mix(0.72,0.58,h),r*0.86);
    // Ground haze under the horizon.
    c*=mix(1.0,0.78,sat(-ray.y*4.0));

    // The sun's halo (Mie), in the sky and in the haze in front of the land. Restrained: a glow round the
    // sun, not a yellow fog over everything.
    float glowHeight=smoothstep(-0.06,0.03,altitude)*(1.0-r);
    vec3 halo=mix(vec3(1.0,0.93,0.82),vec3(1.0,0.66,0.34),twilight());
    float around=sat(cosSun);
    c+=halo*(pow(around,10.0)*mix(0.030,0.075,tw)+pow(around,120.0)*mix(0.10,0.24,tw))*glowHeight*float(SUN_GLOW);

    if(celestial && float(SKY_AURORA)>0.0)c+=aurora(ray)*float(SKY_AURORA)*float(LIGHT_NIGHT);
    if(!celestial)return c;
    float md=dot(ray,moonDir());
    float sunSize=clamp(float(SUN_SIZE),0.25,4.0);
#if LIGHTSHADE_AURORA
    // Bright enough for a soft bloom, not a white-out. It grows a little toward the horizon.
    float grown=sunSize*mix(1.0,1.30,twilight());
    float disc=smoothstep(cos(0.0175*grown),cos(0.0145*grown),cosSun);
    float aureole=pow(around,700.0/grown)*0.30+pow(around,5000.0/(grown*grown))*0.9;
    vec3 hot=mix(vec3(1.0,0.94,0.82),vec3(1.0,0.68,0.36),twilight());
    c+=hot*(disc*12.0+aureole)*float(SUN_GLOW)*(1.0-r)*smoothstep(-0.045,0.005,altitude);
#else
    float disc=smoothstep(cos(0.0175*sunSize),cos(0.0145*sunSize),cosSun);
    float aureole=pow(around,64.0)*0.04+pow(around,640.0/sunSize)*0.16;
    c+=sunlightColor()*(disc*3.5+aureole)*(1.0-r)*smoothstep(-0.045,0.005,altitude);
#endif
    float moonSize=clamp(float(MOON_SIZE),0.25,4.0);
    float moon=smoothstep(cos(0.0145*moonSize),cos(0.0125*moonSize),md);
    if(moon>0.0 && day<1.0){
        vec3 m=moonDir(),side=normalize(cross(m,vec3(0.707,0,0.707)));
        vec2 q=vec2(dot(ray,side),dot(ray,cross(side,m)))/sin(0.0135*moonSize);
        float phase=LsMoon.w*PI*0.25;
        float litSide=smoothstep(-0.05,0.05,dot(vec3(q,sqrt(max(0.0,1.0-dot(q,q)))),vec3(sin(phase),0,cos(phase))));
        // Maria: a few soft darker patches, fixed to the disc.
        float maria=smoothNoise(vec3(q*3.1,4.0))*0.5+smoothNoise(vec3(q*6.3,9.0))*0.25;
        vec3 surface=vec3(0.36,0.41,0.50)*(1.0-0.30*smoothstep(0.42,0.62,maria));
        c+=moon*surface*(0.015+0.985*litSide)*(1.0-day)*(1.0-r);
        c+=moonlightColor()*pow(max(md,0.0),900.0/moonSize)*0.010*(1.0-day)*(1.0-r);
    }
#if LIGHTSHADE_AURORA
    // A soft halo in the thin air around the moon, also when the disc is below the screen's edge.
    c+=moonlightColor()*(pow(max(md,0.0),160.0/moonSize)*0.012+pow(max(md,0.0),24.0)*0.0035)*(1.0-day)*(1.0-r)*smoothstep(-0.02,0.08,moonDir().y)*float(LIGHT_NIGHT);
#endif
    return c;
}
// Celestial discs belong only to the visible sky and reflections; fog must not draw a sun or an aurora
// through walls.
vec3 skyColor(vec3 ray){return skyRadiance(ray,true);}
vec3 skyFogColor(vec3 ray){return skyRadiance(ray,false);}
vec3 starField(vec3 ray,float footprint){
    if(!isOverworld()&&!isEnd())return vec3(0);
    float visible=isEnd()?0.8:(1.0-daylight())*(1.0-rainAmount())*smoothstep(0.0,0.20,ray.y);
#if LIGHTSHADE_AURORA
    if(visible<=0.001)return vec3(0);
    vec3 sum=vec3(0);
    // Two layers: a few bright stars that twinkle gently, and a fine dust of faint ones.
    for(int layer=0;layer<2;layer++){
        float scale=layer==0?230.0:520.0;
        vec3 cell=floor(ray*scale),q=fract(ray*scale)-0.5,offset=(vec3(hashValue(cell+3.0),hashValue(cell+7.0),hashValue(cell+11.0))-0.5)*0.55;
        float chance=hashValue(cell);
        float threshold=layer==0?0.9935:0.982;
        if(chance<threshold)continue;
        float size=layer==0?44.0:80.0,power=(chance-threshold)/(1.0-threshold);
        float twinkle=layer==0?0.82+0.18*sin(LsTime.x*(2.0+hashValue(cell+5.0)*4.0)+chance*90.0):1.0;
        vec3 tone=mix(vec3(0.46,0.64,1.0),vec3(1.0,0.74,0.46),hashValue(cell+41.0));
        tone=mix(vec3(0.85),tone,0.7);
        float d=dot(q-offset,q-offset);
        sum+=tone*exp(-d*size)*mix(0.10,layer==0?1.9:0.34,power*power)*twinkle;
    }
    vec3 result=sum*visible*(1.0-smoothstep(0.8,2.0,footprint*320.0));
    if(isOverworld()&&float(SKY_MILKY_WAY)>0.0){
        // The galaxy as a tilted band of light with dark dust lanes along its core.
        vec3 pole=normalize(vec3(0.42,0.34,0.84));
        float latitude=dot(ray,pole)+(smoothNoise(ray*3.0+5.0)-0.5)*0.10;
        float band=exp(-latitude*latitude*11.0),core=exp(-latitude*latitude*70.0);
        float cloud=smoothNoise(ray*5.0)*0.50+smoothNoise(ray*11.0+2.0)*0.30+smoothNoise(ray*23.0+4.0)*0.20;
        float dust=smoothstep(0.42,0.64,smoothNoise(ray*7.0+9.0)*0.62+smoothNoise(ray*17.0+1.0)*0.38);
        vec3 glow=mix(vec3(0.11,0.15,0.30),vec3(0.40,0.32,0.28),core);
        result+=glow*band*(0.22+1.25*cloud*cloud)*(1.0-dust*core*0.85)*0.11*visible*float(SKY_MILKY_WAY);
    }
    if(isOverworld()&&float(SKY_SHOOTING_STARS)>0.0){
        // Now and then a meteor: each time slot picks a path, or none.
        float period=11.0/float(SKY_SHOOTING_STARS),slot=floor(LsTime.x/period),life=fract(LsTime.x/period)*period/0.85;
        if(life<1.0&&hashValue(vec3(slot,3.0,1.0))>0.45){
            vec3 a=normalize(vec3(hashValue(vec3(slot,1.0,7.0))*2.0-1.0,0.42+hashValue(vec3(slot,2.0,7.0))*0.5,hashValue(vec3(slot,3.0,7.0))*2.0-1.0));
            vec3 b=normalize(a+normalize(vec3(hashValue(vec3(slot,4.0,9.0))-0.5,-0.32,hashValue(vec3(slot,5.0,9.0))-0.5))*0.34);
            vec3 head=normalize(mix(a,b,life)),tail=normalize(mix(a,b,max(life-0.34,0.0)));
            vec3 along=head-tail;float span=max(dot(along,along),1e-6);
            float t=sat(dot(ray-tail,along)/span);
            vec3 nearest=tail+along*t;
            float d=length(ray-nearest);
            result+=vec3(0.75,0.86,1.0)*exp(-d*d*260000.0)*t*t*sin(life*PI)*1.5*visible;
        }
    }
    return result;
#else
    vec3 cell=floor(ray*320.0),q=fract(ray*320.0)-0.5;
    float intensity=exp(-dot(q,q)*58.0)*step(0.995,hashValue(cell));
    return mix(vec3(0.40,0.61,0.92),vec3(0.85,0.64,0.37),hashValue(cell+41.0))*intensity*visible*(1.0-smoothstep(0.8,2.0,footprint*320.0));
#endif
}
#endif
