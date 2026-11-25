package no.julian.lightshade.render;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/** Render-thread queue. Enqueueing must never touch the active GPU encoder. */
final class DeferredGpuCleanup {
    private final ArrayDeque<Runnable> pending=new ArrayDeque<>();

    void defer(Runnable cleanup){pending.addLast(cleanup);}
    int size(){return pending.size();}

    /** The caller must be outside every render pass before inserting the fence. */
    void fence(Consumer<Runnable> submit){
        if(pending.isEmpty())return;
        List<Runnable> batch=List.copyOf(pending);
        submit.accept(()->batch.forEach(Runnable::run));
        // Keep the work queued if fence creation failed. Remove only this batch.
        for(int i=0;i<batch.size();i++)pending.removeFirst();
    }

    /** Shutdown path, after native rendering has stopped. */
    void closeNow(){while(!pending.isEmpty())pending.removeFirst().run();}
}
