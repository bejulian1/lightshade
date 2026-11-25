package no.julian.lightshade.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.*;

/** Optional per-stage GPU timing (-Dlightshade.profile=true). Timestamps go between
 * passes, never inside one; results are read three frames later and averaged over the last 120 frames. */
final class GpuProfile {
    static final boolean ENABLED=Boolean.getBoolean("lightshade.profile");
    private static final int FRAMES=4,SLOTS=48,WINDOW=120;
    private final GpuQueryPool[] pools=new GpuQueryPool[FRAMES];
    @SuppressWarnings({"unchecked","rawtypes"}) private final List<String>[] labels=new List[FRAMES];
    private final Map<String,double[]> history=new LinkedHashMap<>();
    private int frame,cursor;
    private boolean open;

    /** Starts a frame's timeline; the first mark is its zero. */
    void begin(){
        if(!ENABLED)return;
        frame=(frame+1)%FRAMES;
        if(pools[frame]!=null){collect(frame);}
        else pools[frame]=RenderSystem.getDevice().createTimestampQueryPool(SLOTS);
        labels[frame]=new ArrayList<>();open=true;
        mark("start");
    }
    /** Time since the previous mark is booked under this label. */
    void mark(String label){
        if(!ENABLED||!open||labels[frame].size()>=SLOTS)return;
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pools[frame],labels[frame].size());
        labels[frame].add(label);
    }
    private void collect(int index){
        List<String> names=labels[index];if(names==null||names.size()<2)return;
        OptionalLong[] values=pools[index].getValues(0,names.size());
        double period=RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
        for(int i=1;i<names.size();i++){
            if(values[i].isEmpty()||values[i-1].isEmpty())return;
            double ms=(values[i].orElseThrow()-values[i-1].orElseThrow())*period/1e6;
            if(ms<0||ms>1000)return;
            double[] slot=history.computeIfAbsent(names.get(i),k->new double[WINDOW+1]);
            slot[cursor%WINDOW]=ms;slot[WINDOW]=Math.min(WINDOW,slot[WINDOW]+1);
        }
        cursor++;
    }
    Map<String,Object> status(){
        Map<String,Object> result=new LinkedHashMap<>();
        history.forEach((name,slot)->{int n=(int)slot[WINDOW];double sum=0;for(int i=0;i<n;i++)sum+=slot[i];result.put(name,n==0?0:Math.round(sum/n*1000)/1000.0);});
        return result;
    }
    void close(){for(GpuQueryPool pool:pools)if(pool!=null)pool.close();Arrays.fill(pools,null);history.clear();open=false;}
}
