package no.julian.lightshade.render;

import com.google.gson.*;
import com.mojang.renderpearl.api.GpuFormat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/** Original bounded, immutable Lightshade v1 pack and render-graph parser. */
public record RenderPack(String id, String name, String sha256, String vertex, String fragment,
                         List<Pass> passes, Map<String,String> files, Map<String,Double> options,
                         long budgetBytes) {
    public record Pass(String name, String fragment, Map<String,String> inputs, String output,
                       double scale, GpuFormat format) {}
    private static final Set<String> INPUTS=Set.of("$scene","$depth","$opaque","$opaqueDepth","$shadow","$noise");
    private static final int MAX_FILES=256,MAX_FILE=1024*1024,MAX_TOTAL=16*1024*1024;

    public static RenderPack load(Path directory,String id,Map<String,Double> overrides) throws Exception {
        if(BuiltinPacks.has(id))return parse(id,BuiltinPacks.files(id),overrides);
        if(!id.matches("[A-Za-z0-9_. -]+") || id.equals(".") || id.equals(".."))throw new IOException("Invalid pack filename");
        Path root=directory.toAbsolutePath().normalize(),path=root.resolve(id).normalize();
        if(!path.startsWith(root))throw new IOException("Pack escapes shaderpack directory");
        Map<String,String> files=new TreeMap<>();int total=0;
        if(Files.isDirectory(path)){
            if(Files.isSymbolicLink(path))throw new IOException("Pack directory may not be a symlink");
            try(var walk=Files.walk(path)){
                for(Path file:walk.filter(Files::isRegularFile).sorted().toList()){
                    String name=path.relativize(file).toString().replace('\\','/');
                    if(!isSource(name))continue;
                    if(Files.isSymbolicLink(file)||!file.toRealPath().startsWith(path.toRealPath()))throw new IOException("Pack symlink escapes root");
                    byte[] data=readBounded(Files.newInputStream(file));total+=data.length;
                    files.put(validPath(name),new String(data,StandardCharsets.UTF_8));
                    bounds(files.size(),total);
                }
            }
        }else{
            try(ZipFile archive=new ZipFile(path.toFile())){
                var entries=archive.entries();
                while(entries.hasMoreElements()){
                    var entry=entries.nextElement();String name=entry.getName();
                    if(entry.isDirectory()||!isSource(name))continue;
                    name=validPath(name);if(files.containsKey(name))throw new IOException("Duplicate pack entry "+name);
                    byte[] data=readBounded(archive.getInputStream(entry));total+=data.length;
                    files.put(name,new String(data,StandardCharsets.UTF_8));bounds(files.size(),total);
                }
            }
        }
        return parse(id,files,overrides);
    }
    public static RenderPack parse(String id,Map<String,String> source,Map<String,Double> overrides)throws Exception{
        Map<String,String> files=Map.copyOf(source);
        String manifest=files.get("lightshade.json");if(manifest==null)throw new IOException("Missing lightshade.json at pack root");
        JsonObject root=JsonParser.parseString(manifest).getAsJsonObject();
        if(root.get("version").getAsInt()!=1)throw new IOException("Only Lightshade manifest version 1 is supported");
        String name=root.has("name")?root.get("name").getAsString():id;
        JsonObject terrain=root.getAsJsonObject("terrain");
        if(terrain==null)throw new IOException("A shader pack must supply original terrain vertex and fragment programs");
        String vertex=requiredSource(files,terrain.get("vertex").getAsString());
        String fragment=requiredSource(files,terrain.get("fragment").getAsString());
        Map<String,Double> options=new TreeMap<>();
        if(root.has("options"))for(var entry:root.getAsJsonObject("options").entrySet()){
            if(!entry.getKey().matches("[A-Z][A-Z0-9_]{0,63}"))throw new IOException("Invalid shader option "+entry.getKey());
            double value=entry.getValue().isJsonObject()?entry.getValue().getAsJsonObject().get("default").getAsDouble():entry.getValue().getAsDouble();
            options.put(entry.getKey(),value);
        }
        for(var entry:overrides.entrySet())if(options.containsKey(entry.getKey())){
            JsonElement definition=root.getAsJsonObject("options").get(entry.getKey());
            if(definition.isJsonObject()&&definition.getAsJsonObject().has("values")){
                boolean allowed=false;for(JsonElement choice:definition.getAsJsonObject().getAsJsonArray("values"))if(Double.compare(choice.getAsDouble(),entry.getValue())==0)allowed=true;
                if(!allowed)throw new IOException("Unsupported option value "+entry.getKey()+"="+entry.getValue());
            }
            options.put(entry.getKey(),entry.getValue());
        }
        for(var entry:options.entrySet())if(!Double.isFinite(entry.getValue())||Math.abs(entry.getValue())>1000000)throw new IOException("Non-finite/out-of-range option "+entry.getKey());
        List<Pass> passes=new ArrayList<>();Set<String> available=new HashSet<>(INPUTS),passNames=new HashSet<>();
        JsonArray graph=root.getAsJsonArray("passes");
        if(graph==null||graph.isEmpty()||graph.size()>32)throw new IOException("Graph must have 1..32 passes");
        for(int i=0;i<graph.size();i++){
            JsonObject node=graph.get(i).getAsJsonObject();String passName=node.get("name").getAsString();
            if(!passName.matches("[a-z][a-z0-9_]{0,47}")||!passNames.add(passName))throw new IOException("Invalid/duplicate pass name "+passName);
            String shader=requiredSource(files,node.get("fragment").getAsString()),output=node.get("output").getAsString();
            if(output.equals("$screen")){if(i!=graph.size()-1)throw new IOException("Screen must be written only by final pass");}
            else if(!output.matches("[a-z][a-z0-9_]{0,47}")||available.contains(output))throw new IOException("Invalid/reused graph output "+output);
            Map<String,String> inputs=new LinkedHashMap<>();
            for(var input:node.getAsJsonObject("inputs").entrySet()){
                if(!input.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IOException("Invalid sampler name");
                String target=input.getValue().getAsString();
                if(!available.contains(target))throw new IOException("Pass "+passName+" reads undefined/future target "+target);
                if(target.equals(output))throw new IOException("A pass cannot sample its render attachment");
                inputs.put(input.getKey(),target);
            }
            double scale=node.has("scale")?node.get("scale").getAsDouble():1;
            if(!Double.isFinite(scale)||scale<.125||scale>1)throw new IOException("Pass scale must be 0.125..1");
            GpuFormat format=node.has("format")?GpuFormat.valueOf(node.get("format").getAsString()):GpuFormat.RGBA8_UNORM;
            if(format!=GpuFormat.RGBA8_UNORM&&format!=GpuFormat.RGBA16_FLOAT)throw new IOException("Unsupported graph color format");
            if(output.equals("$screen")&&(scale!=1||format!=GpuFormat.RGBA8_UNORM))throw new IOException("Final pass must be full-size display-encoded RGBA8");
            passes.add(new Pass(passName,shader,Map.copyOf(inputs),output,scale,format));available.add(output);
        }
        if(!passes.getLast().output().equals("$screen"))throw new IOException("Graph must finish at $screen");
        long budget=(root.has("budgetMiB")?root.get("budgetMiB").getAsNumber().longValue():256)*1024L*1024L;
        if(budget<16*1024L*1024L||budget>1024L*1024*1024)throw new IOException("Budget must be 16..1024 MiB");
        var digest=MessageDigest.getInstance("SHA-256");
        for(var entry:new TreeMap<>(files).entrySet()){digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));}
        return new RenderPack(id,name,HexFormat.of().formatHex(digest.digest()),vertex,fragment,List.copyOf(passes),files,Map.copyOf(options),budget);
    }
    private static String requiredSource(Map<String,String> files,String path)throws IOException{
        path=validPath(path);if(!files.containsKey(path))throw new IOException("Missing shader "+path);return path;
    }
    private static String validPath(String path)throws IOException{
        if(path.startsWith("/")||path.contains("\\")||!path.matches("[A-Za-z0-9_./-]+")||List.of(path.split("/")).contains(".."))throw new IOException("Unsafe pack resource path");return path;
    }
    private static boolean isSource(String name){return name.equals("lightshade.json")||name.endsWith(".glsl")||name.endsWith(".vsh")||name.endsWith(".fsh");}
    private static byte[] readBounded(InputStream input)throws IOException{try(input){byte[] bytes=input.readNBytes(MAX_FILE+1);if(bytes.length>MAX_FILE)throw new IOException("Shader exceeds 1 MiB");return bytes;}}
    private static void bounds(int files,int bytes)throws IOException{if(files>MAX_FILES||bytes>MAX_TOTAL)throw new IOException("Pack source limits exceeded");}
}
