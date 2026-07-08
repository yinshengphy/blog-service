package cn.yinsheng.blog.rag.ratelimit;

import cn.yinsheng.blog.rag.config.RagProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class RateLimitService {
  private static final long MINUTE_MS = 60_000L;
  private static final long DAY_MS = 86_400_000L;

  private final RagProperties properties;

  public RateLimitService(RagProperties properties) {
    this.properties = properties;
  }

  public synchronized void checkAndRecord(String clientIp) {
    ensureSchema();
    long now = System.currentTimeMillis();
    String ipHash = sha256(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);

    try (Connection connection = connect()) {
      deleteOldEvents(connection, now - DAY_MS);
      int minuteCount = countSince(connection, ipHash, now - MINUTE_MS);
      int dayCount = countSince(connection, ipHash, now - DAY_MS);
      if (minuteCount >= Math.max(1, properties.rateLimitPerMinute())
          || dayCount >= Math.max(1, properties.rateLimitPerDay())) {
        throw new RateLimitExceededException("今天聊得有点多啦，稍后再试试。");
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "insert into chat_rate_limit_events(ip_hash, created_at) values(?, ?)")) {
        statement.setString(1, ipHash);
        statement.setLong(2, now);
        statement.executeUpdate();
      }
    } catch (RateLimitExceededException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to check chat rate limit", ex);
    }
  }

  private void deleteOldEvents(Connection connection, long cutoff) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(
        "delete from chat_rate_limit_events where created_at < ?")) {
      statement.setLong(1, cutoff);
      statement.executeUpdate();
    }
  }

  private int countSince(Connection connection, String ipHash, long since) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(
        "select count(*) from chat_rate_limit_events where ip_hash = ? and created_at >= ?")) {
      statement.setString(1, ipHash);
      statement.setLong(2, since);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : 0;
      }
    }
  }

  private void ensureSchema() {
    try {
      Path path = Path.of(properties.rateLimitDbPath());
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      try (Connection connection = connect();
           Statement statement = connection.createStatement()) {
        statement.executeUpdate("""
            create table if not exists chat_rate_limit_events (
              ip_hash text not null,
              created_at integer not null
            )
            """);
        statement.executeUpdate("""
            create index if not exists idx_chat_rate_limit_events_ip_time
            on chat_rate_limit_events(ip_hash, created_at)
            """);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to ensure rate limit schema", ex);
    }
  }

  private Connection connect() throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + properties.rateLimitDbPath());
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to calculate IP hash", ex);
    }
  }
}
