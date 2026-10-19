package dev.sid.fiveberinspector;
import java.io.*;
/** Enforce the budget during reading, before a hostile line can allocate unbounded memory. */
final class BoundedLines {
 static String read(Reader in,int limit)throws IOException {
  if(limit<1)throw new IllegalArgumentException("Invalid line budget");
  StringBuilder line=new StringBuilder(Math.min(limit,1024));
  for(;;){int c=in.read();if(c<0)return line.length()==0?null:line.toString();
   if(c=='\n')return line.toString();
   if(line.length()>=limit)throw new IOException("IPC line budget exceeded");line.append((char)c);
  }
 }
 private BoundedLines(){}
}
