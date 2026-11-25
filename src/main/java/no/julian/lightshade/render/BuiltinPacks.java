package no.julian.lightshade.render;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The packs shipped inside the loader JAR (assets/lightshade/packs/<id>/, listed in index.txt).
 * tools/build.py assembles them from packs/common plus each pack's own files. */
public final class BuiltinPacks {
    /** Menu order: the light pack first. */
    public static final List<String> IDS=List.of("daylight","aurora");
    public static final String DEFAULT="daylight";
    private static final Map<String,Map<String,String>> CACHE=new ConcurrentHashMap<>();
    private BuiltinPacks(){}
    public static boolean has(String id){return IDS.contains(id);}
    static Map<String,String> files(String id)throws IOException{
        if(!has(id))throw new IOException("Unknown built-in pack "+id);
        Map<String,String> cached=CACHE.get(id);if(cached!=null)return cached;
        String base=base(id);
        Map<String,String> files=new TreeMap<>();
        for(String name:read(base+"index.txt").split("\n")){
            name=name.strip();if(name.isEmpty())continue;
            files.put(name,read(base+name));
        }
        Map<String,String> frozen=Map.copyOf(files);CACHE.put(id,frozen);return frozen;
    }
    /** The pack's 64x64 icon as PNG bytes, or null. */
    public static byte[] icon(String id){
        if(!has(id))return null;
        try(InputStream input=BuiltinPacks.class.getResourceAsStream(base(id)+"pack.png")){
            return input==null?null:input.readNBytes(256*1024);
        }catch(IOException failure){return null;}
    }
    private static String base(String id){return "/assets/lightshade/packs/"+id+"/";}
    private static String read(String resource)throws IOException{
        try(InputStream input=BuiltinPacks.class.getResourceAsStream(resource)){
            if(input==null)throw new FileNotFoundException("Missing built-in pack file "+resource);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
