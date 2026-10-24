package android.os;
import java.util.*;
public final class Handler {
 private static final Map<Handler,ArrayDeque<Runnable>> queues=new LinkedHashMap<>();
 public Handler(Looper looper){queues.put(this,new ArrayDeque<>());}
 public boolean postDelayed(Runnable task,long delay){queues.get(this).add(task);return true;}
 public void removeCallbacksAndMessages(Object token){queues.get(this).clear();}
 public static void drain(){
  for(;;){Runnable task=null;for(ArrayDeque<Runnable> q:queues.values()){if(!q.isEmpty()){task=q.remove();break;}}
   if(task==null)return;task.run();}
 }
}
