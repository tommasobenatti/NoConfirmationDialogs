package io.github.tommasobenatti.ncd;
import com.google.gson.JsonParser;
import java.lang.management.ManagementFactory;
import java.util.*;
/** Isolated microbenchmark; excludes host serialization and is not a server TPS claim. */
public final class PerfProbe {
 static Object sink;
 static void measure(String name, int n, Runnable operation) {
  for(int i=0;i<20000;i++) operation.run();
  var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  long thread=Thread.currentThread().threadId();
  long bytes=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
  for(int i=0;i<n;i++) operation.run();
  long duration=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(thread)-bytes;
  System.out.printf(Locale.ROOT,"{\"metric\":\"%s\",\"ns_per_op\":%.1f,\"bytes_per_op\":%.1f}%n",name,(double)duration/n,(double)allocated/n);
 }
 public static void main(String[] args)throws Exception {
  var clock=new java.util.concurrent.atomic.AtomicLong();
  var c=new CommandClicks(clock::get,4096,43200000);
  var plain=JsonParser.parseString("{\"text\":\"A normal message\",\"color\":\"gold\",\"extra\":[{\"text\":\"hello\"}]}");
  var click=JsonParser.parseString("{\"text\":\"Accept\",\"click_event\":{\"action\":\"run_command\",\"command\":\"/huskhomes:tpaccept Alice\"}}");
  measure("plain_component",200000,()->sink=c.rewrite(plain));
  measure("click_component",100000,()->sink=c.rewrite(click));
  measure("invalid_token",200000,()->sink=c.resolve("ncd:run/not-registered"));
  var stress=new CommandClicks(()->0L,4096,43200000);
  String filler="x".repeat(8192); long start=System.nanoTime();
  for(int i=0;i<4096;i++)stress.register("help "+i+" "+filler);
  var field=CommandClicks.class.getDeclaredField("entries");field.setAccessible(true);
  var entries=(Map<?,?>)field.get(stress);long chars=0;
  for(var entry:entries.values()){var m=entry.getClass().getDeclaredMethod("command");m.setAccessible(true);chars+=((String)m.invoke(entry)).length();}
  System.out.printf(Locale.ROOT,"{\"metric\":\"retained_stress\",\"entries\":%d,\"command_characters\":%d,\"elapsed_ms\":%.2f}%n",stress.size(),chars,(System.nanoTime()-start)/1e6);
 }
}
