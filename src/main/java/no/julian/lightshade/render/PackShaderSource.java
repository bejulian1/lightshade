package no.julian.lightshade.render;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Own sources plus read-only runtime includes supplied by Minecraft/Sodium. */
final class PackShaderSource implements ShaderSource {
    private final RenderPack pack;
    private final Map<Identifier,String> shaders=new HashMap<>();
    private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
    PackShaderSource(RenderPack pack){this.pack=pack;}
    void put(Identifier id,String text){shaders.put(id,text);}
    void path(Identifier id,String path){put(id,pack.files().get(path));}
    @Override public String getShader(Identifier id,ShaderType type){
        String text=shaders.get(id);
        if(text==null)throw new IllegalArgumentException("Unknown Lightshade shader "+id+" "+type);
        return text;
    }
    @Override public synchronized CachedIncludeSource getInclude(Identifier id){
        return includes.computeIfAbsent(id,key->{
            try{
                if(key.getNamespace().equals("lightshade")){
                    String text=pack.files().get("shaders/include/"+key.getPath());
                    if(text==null)text=pack.files().get("shaders/"+key.getPath());
                    if(text==null)throw new IllegalArgumentException("Missing Lightshade include "+key);
                    return CachedIncludeSource.create(key,text);
                }
                if(!Set.of("minecraft","sodium").contains(key.getNamespace()))throw new IllegalArgumentException("Unsupported include namespace "+key);
                var resource=Minecraft.getInstance().getResourceManager().getResource(Identifier.fromNamespaceAndPath(key.getNamespace(),"shaders/include/"+key.getPath())).orElseThrow();
                try(var input=resource.open()){
                    byte[] bytes=input.readNBytes(1024*1024+1);
                    if(bytes.length>1024*1024)throw new IllegalArgumentException("Include exceeds limit");
                    return CachedIncludeSource.create(key,new String(bytes,StandardCharsets.UTF_8));
                }
            }catch(Exception error){return CachedIncludeSource.createError("Lightshade include "+key+": "+error.getMessage());}
        });
    }
    @Override public synchronized void close(){includes.values().forEach(CachedIncludeSource::close);includes.clear();shaders.clear();}
}
