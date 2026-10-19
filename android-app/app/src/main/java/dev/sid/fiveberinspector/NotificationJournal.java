package dev.sid.fiveberinspector;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Separate durable notification records. Never stores signed payloads, activation codes or raw card IDs. */
final class NotificationJournal {
  private final File directory;
  NotificationJournal(File directory) throws IOException {
    this.directory=directory;
    if(!directory.getCanonicalFile().equals(directory.getAbsoluteFile())||(!directory.isDirectory()&&!directory.mkdirs()))throw new IOException("Notification journal unavailable");
    Files.setPosixFilePermissions(directory.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  }
  synchronized List<Properties> records() throws IOException {
    File[] pending=directory.listFiles((dir,name)->name.endsWith(".pending"));
    if(pending==null||pending.length!=0)throw new IOException("Interrupted notification commit; review required");
    File[] files=directory.listFiles((dir,name)->name.endsWith(".properties"));
    if(files==null||files.length>128)throw new IOException("Notification journal capacity");
    List<Properties> records=new ArrayList<>();
    for(File f:files) {
      if(Files.isSymbolicLink(f.toPath())||f.length()>4096)throw new IOException("Invalid notification record");
      Properties p=new Properties();try(InputStream in=new FileInputStream(f)){p.load(in);}
      if(!p.getProperty("id", "").matches("[a-f0-9-]{36}")||!f.getName().equals(p.getProperty("id")+".properties")
          ||!p.getProperty("status", "").matches("prepared|posting|acknowledged|removing|delivered|blocked_recipient|unavailable")
          ||!p.getProperty("card", "").matches("(?:[A-F0-9]{16}|[a-f0-9]{64})")||!p.getProperty("profile", "").matches("[a-f0-9]{64}")
          ||!p.getProperty("sequence", "").matches("[0-9]{1,10}")||!p.getProperty("startedAt", "").matches("[0-9]{1,20}"))throw new IOException("Invalid notification record");
      try {long seq=Long.parseLong(p.getProperty("sequence"));NotificationCodec.command(seq,false);ActivationCode.validateHost(p.getProperty("host"));}
      catch(RuntimeException invalid){throw new IOException("Invalid notification metadata");}
      if(p.containsKey("transaction")&&(!p.getProperty("transaction").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
          ||!p.getProperty("card").matches("[a-f0-9]{64}")))throw new IOException("Invalid notification transaction binding");
      records.add(p);
    }
    records.sort(Comparator.comparingLong(p->Long.parseLong(p.getProperty("startedAt"))));return records;
  }
  synchronized Properties begin(String card,long sequence,String host,String profile) throws IOException {
    return beginInternal(card,sequence,host,profile,null);
  }
  synchronized List<Properties> recordsForCard(String card) throws IOException {
    if(card==null||!card.matches("[a-f0-9]{64}"))throw new IOException("Complete card identity required");
    List<Properties> selected=new ArrayList<>();
    for(Properties record:records())if(card.equals(record.getProperty("card")))selected.add(record);
    return selected;
  }
  synchronized Properties beginBound(String card,long sequence,String host,String profile,String transaction) throws IOException {
    if(card==null||!card.matches("[a-f0-9]{64}")||transaction==null
        ||!transaction.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("Complete card and transaction binding required");
    return beginInternal(card,sequence,host,profile,transaction);
  }
  private Properties beginInternal(String card,long sequence,String host,String profile,String transaction) throws IOException {
    List<Properties> prior=records();if(prior.size()>=128)throw new IOException("Notification journal full");
    for(Properties p:prior)if(card.equals(p.getProperty("card"))&&Long.toString(sequence).equals(p.getProperty("sequence")))throw new IOException("Notification attempt already recorded");
    NotificationCodec.command(sequence,false);
    if(!card.matches("(?:[A-F0-9]{16}|[a-f0-9]{64})")||!profile.matches("[a-f0-9]{64}"))throw new IOException("Invalid notification identity");
    Properties p=new Properties();p.setProperty("id",UUID.randomUUID().toString());p.setProperty("status","prepared");p.setProperty("card",card);p.setProperty("profile",profile);
    p.setProperty("sequence",Long.toString(sequence));p.setProperty("host",ActivationCode.validateHost(host));p.setProperty("startedAt",Long.toString(System.currentTimeMillis()));
    if(transaction!=null)p.setProperty("transaction",transaction);
    save(p);return p;
  }
  synchronized void advance(Properties p,String next) throws IOException {
    String current=p.getProperty("status");
    boolean legal=(current.equals("prepared")&&(next.equals("posting")||next.equals("blocked_recipient")||next.equals("unavailable")))
        ||current.equals("posting")&&next.equals("acknowledged")
        ||(current.equals("acknowledged")||current.equals("removing"))&&next.equals("removing")
        ||current.equals("removing")&&next.equals("delivered");
    if(!legal)throw new IOException("Invalid notification journal transition");
    Properties update=new Properties();update.putAll(p);update.setProperty("status",next);save(update);p.setProperty("status",next);
  }
  static String outcome(Properties p) {
    switch(p.getProperty("status")) {
      case "delivered":return "delivered";
      case "posting":return "delivery_unknown";
      case "acknowledged":case "removing":return "acknowledged_pending_cleanup";
      case "blocked_recipient":return "blocked_recipient";
      case "prepared":return "not_started";
      default:return "unavailable";
    }
  }
  static boolean cleanup(Properties p){return "acknowledged_pending_cleanup".equals(outcome(p));}
  private void save(Properties p) throws IOException {
    String id=p.getProperty("id");File temp=new File(directory,id+".pending");
    if(Files.isSymbolicLink(temp.toPath()))throw new IOException("Unsafe notification journal");
    try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(temp.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
      Files.setPosixFilePermissions(temp.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      OutputStream out=java.nio.channels.Channels.newOutputStream(channel);p.store(out,"Notification delivery metadata only");out.flush();channel.force(true);
    }
    Files.move(temp.toPath(),new File(directory,id+".properties").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    try(java.nio.channels.FileChannel dir=java.nio.channels.FileChannel.open(directory.toPath(),StandardOpenOption.READ)){dir.force(true);}
  }
}
