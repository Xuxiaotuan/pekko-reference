import java.sql.*;
import java.util.*;
public class CacheProbe {
 static String url="jdbc:mysql://127.0.0.1:25822/pekko_trace_semantics_20261108?useSSL=false&allowPublicKeyRetrieval=true";
 static String user=System.getenv("LAB_USER"), pass=System.getenv("LAB_PASS");
 static Connection open() throws Exception{return DriverManager.getConnection(url,user,pass);}
 static String query(Connection c,String sql) throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){StringBuilder b=new StringBuilder();int n=r.getMetaData().getColumnCount();while(r.next()){if(b.length()>0)b.append('|');for(int i=1;i<=n;i++){if(i>1)b.append(',');b.append(r.getString(i));}}return b.toString();}}
 static void event(String tag,String details){System.out.println(System.nanoTime()+"\t"+System.currentTimeMillis()+"\t"+tag+"\t"+details);System.out.flush();}
 static void run(int interval,int seq) throws Exception {
  try(Connection target=open(); Connection obs=open()){
   target.setAutoCommit(false);String id=query(target,"SELECT CONNECTION_ID()");
   String thread=query(obs,"SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID="+id);
   event("start","interval_ms="+interval+" seq="+seq+" conn="+id+" thread="+thread+" observer="+query(obs,"SELECT CONNECTION_ID()")+" observer_autocommit="+obs.getAutoCommit());
   query(target,"SELECT id,value FROM trace_probe ORDER BY id"); Thread.sleep(200);
   String trx="SELECT trx_mysql_thread_id,trx_id,trx_started,trx_is_read_only,trx_state FROM information_schema.innodb_trx WHERE trx_mysql_thread_id="+id;
   String pfs="SELECT THREAD_ID,EVENT_ID,STATE,TRX_ID,ACCESS_MODE,AUTOCOMMIT FROM performance_schema.events_transactions_current WHERE THREAD_ID="+thread;
   event("before_tx",query(obs,trx)); event("before_pfs",query(obs,pfs));
   long begin=System.nanoTime();event("close_begin",id);target.close();event("close_return",id);
   long end=System.nanoTime()+1500000000L;int k=0;
   while(System.nanoTime()<end){long t=System.nanoTime();String result=query(obs,trx);event("poll", "seq="+(k++)+" elapsed_ms="+((System.nanoTime()-begin)/1_000_000)+" query_us="+((System.nanoTime()-t)/1000)+" trx="+result);Thread.sleep(interval);}
   event("quiet_begin",id);Thread.sleep(600);
   try(Connection fresh=open()) {event("quiet_probe", "conn="+query(fresh,"SELECT CONNECTION_ID()")+" trx="+query(fresh,trx));event("locks",query(fresh,"SELECT ENGINE_TRANSACTION_ID,THREAD_ID,EVENT_ID,LOCK_TYPE,LOCK_MODE,LOCK_STATUS FROM performance_schema.data_locks WHERE THREAD_ID="+thread));event("pfs_history",query(fresh,"SELECT THREAD_ID,EVENT_ID,STATE,TRX_ID,ACCESS_MODE,AUTOCOMMIT FROM performance_schema.events_transactions_history_long WHERE THREAD_ID="+thread));event("pfs_statements",query(fresh,"SELECT THREAD_ID,EVENT_ID,NESTING_EVENT_ID,NESTING_EVENT_TYPE,EVENT_NAME FROM performance_schema.events_statements_history_long WHERE THREAD_ID="+thread));}
  }
 }
 public static void main(String[]args)throws Exception{if(user==null||pass==null)throw new Exception("private credentials absent");Class.forName("com.mysql.cj.jdbc.Driver");event("version",System.getProperty("java.version"));for(int i=1;i<=2;i++)run(20,i);for(int i=1;i<=2;i++)run(300,i);}
}
