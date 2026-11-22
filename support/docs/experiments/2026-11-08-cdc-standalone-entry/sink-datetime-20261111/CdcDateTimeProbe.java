import java.sql.*;
import java.time.*;
public final class CdcDateTimeProbe {
  public static void main(String[] args) throws Exception {
    try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:25823/pekko_test_cdc_window_089514db0cdf?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true", "root", System.getenv("CDC_PROBE_PASSWORD"));
         Statement s=c.createStatement()) {
      s.execute("CREATE TEMPORARY TABLE cdc_datetime_probe (id INT PRIMARY KEY, v DATETIME(6))");
      try (PreparedStatement p=c.prepareStatement("INSERT INTO cdc_datetime_probe VALUES (?,?)")) {
        LocalDateTime v=LocalDateTime.parse("2026-10-27T04:05:06.123456");
        p.setInt(1,1);p.setObject(2,Timestamp.valueOf(v),Types.TIMESTAMP);p.executeUpdate();
        p.setInt(1,2);p.setObject(2,v,Types.TIMESTAMP);p.executeUpdate();
      }
      try (ResultSet r=s.executeQuery("SELECT id, CAST(v AS CHAR) FROM cdc_datetime_probe ORDER BY id")) {
        while(r.next()) System.out.println(r.getInt(1)+"="+r.getString(2));
      }
    }
  }
}
