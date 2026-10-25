package android.os;
import java.util.*;
public final class Handler {
 private static final class Task {
  final Runnable runnable;final long due;
  Task(Runnable runnable,long due){this.runnable=runnable;this.due=due;}
 }
 private static final Map<Handler,List<Task>> queues=new LinkedHashMap<>();
 private static long now;
 public Handler(Looper looper){queues.put(this,new ArrayList<>());}
 public boolean postDelayed(Runnable task,long delay){queues.get(this).add(new Task(task,now+Math.max(0,delay)));return true;}
 public void removeCallbacksAndMessages(Object token){queues.get(this).clear();}
 public static void advanceBy(long millis){
  if(millis<0)throw new IllegalArgumentException();now+=millis;drain();
 }
 public static void drain(){
  for(;;){Task next=null;List<Task> owner=null;
   for(List<Task> q:queues.values())for(Task task:q)
    if(task.due<=now&&(next==null||task.due<next.due)){next=task;owner=q;}
   if(next==null)return;owner.remove(next);next.runnable.run();}
 }
}
