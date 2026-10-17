package cn.yinsheng.blog.service.publication;

import cn.yinsheng.blog.service.config.PublicationProperties;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PublicationStore {
  private final PublicationProperties properties;

  public PublicationStore(PublicationProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void initialize() {
    if (properties.enabled()) {
      ensureSchema();
    }
  }

  public PublicationReceipt enqueue(PublicationKind kind, String idempotencyKey, String payload) {
    ensureSchema();
    try (Connection connection = connect()) {
      try (PreparedStatement existing = connection.prepareStatement(
          "select id, kind, payload, status from publication_outbox where idempotency_key = ?")) {
        existing.setString(1, idempotencyKey);
        try (ResultSet result = existing.executeQuery()) {
          if (result.next()) {
            if (!kind.name().equals(result.getString("kind"))
                || !payload.equals(result.getString("payload"))) {
              throw new IllegalArgumentException("幂等键已用于不同的发布内容");
            }
            return new PublicationReceipt(result.getLong("id"), result.getString("status"), true);
          }
        }
      }
      String now = Instant.now().toString();
      try (PreparedStatement insert = connection.prepareStatement("""
          insert into publication_outbox(
            idempotency_key, kind, payload, status, attempts, available_at, created_at, updated_at
          ) values(?, ?, ?, 'PENDING', 0, ?, ?, ?)
          """, Statement.RETURN_GENERATED_KEYS)) {
        insert.setString(1, idempotencyKey);
        insert.setString(2, kind.name());
        insert.setString(3, payload);
        insert.setString(4, now);
        insert.setString(5, now);
        insert.setString(6, now);
        insert.executeUpdate();
        try (ResultSet keys = insert.getGeneratedKeys()) {
          if (!keys.next()) {
            throw new IllegalStateException("发布任务未返回 ID");
          }
          return new PublicationReceipt(keys.getLong(1), "PENDING", false);
        }
      }
    } catch (Exception ex) {
      throw new IllegalStateException("无法写入博客发布队列", ex);
    }
  }

  public Optional<PublicationItem> claimNext() {
    ensureSchema();
    try (Connection connection = connect(); Statement statement = connection.createStatement()) {
      statement.execute("begin immediate");
      String now = Instant.now().toString();
      try (PreparedStatement recover = connection.prepareStatement("""
          update publication_outbox
          set status = 'PENDING', available_at = ?, updated_at = ?
          where status = 'PROCESSING' and updated_at < ?
          """)) {
        recover.setString(1, now);
        recover.setString(2, now);
        recover.setString(3, Instant.now().minus(20, ChronoUnit.MINUTES).toString());
        recover.executeUpdate();
      }
      PublicationItem item = null;
      try (PreparedStatement select = connection.prepareStatement("""
          select id, idempotency_key, kind, payload, attempts
          from publication_outbox
          where status = 'PENDING' and available_at <= ?
          order by created_at asc
          limit 1
          """)) {
        select.setString(1, now);
        try (ResultSet result = select.executeQuery()) {
          if (result.next()) {
            item = new PublicationItem(
                result.getLong("id"),
                result.getString("idempotency_key"),
                PublicationKind.valueOf(result.getString("kind")),
                result.getString("payload"),
                result.getInt("attempts") + 1
            );
          }
        }
      }
      if (item != null) {
        try (PreparedStatement update = connection.prepareStatement("""
            update publication_outbox set status='PROCESSING', attempts=?, updated_at=? where id=?
            """)) {
          update.setInt(1, item.attempts());
          update.setString(2, now);
          update.setLong(3, item.id());
          update.executeUpdate();
        }
      }
      statement.execute("commit");
      return Optional.ofNullable(item);
    } catch (Exception ex) {
      throw new IllegalStateException("无法领取博客发布任务", ex);
    }
  }

  public void markPublished(long id, String commitSha) {
    update(id, "PUBLISHED", Instant.now().toString(), commitSha, null);
  }

  public void markRetry(long id, int attempts, String error) {
    int safeAttempts = Math.max(1, attempts);
    String status = safeAttempts >= Math.max(1, properties.maxAttempts()) ? "FAILED" : "PENDING";
    long delaySeconds = Math.min(3600, 30L * (1L << Math.min(safeAttempts - 1, 6)));
    String availableAt = Instant.now().plus(delaySeconds, ChronoUnit.SECONDS).toString();
    update(id, status, availableAt, null, error == null ? "unknown publication error" : error);
  }

  public Optional<PublicationStatus> status(String idempotencyKey) {
    ensureSchema();
    try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
        select id, idempotency_key, kind, status, attempts, updated_at, commit_sha, last_error
        from publication_outbox where idempotency_key=?
        """)) {
      statement.setString(1, idempotencyKey);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        return Optional.of(new PublicationStatus(
            result.getLong("id"), result.getString("idempotency_key"), result.getString("kind"),
            result.getString("status"), result.getInt("attempts"), result.getString("updated_at"),
            result.getString("commit_sha"), result.getString("last_error")
        ));
      }
    } catch (Exception ex) {
      throw new IllegalStateException("无法读取博客发布状态", ex);
    }
  }

  private void update(long id, String status, String availableAt, String commitSha, String error) {
    ensureSchema();
    try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
        update publication_outbox
        set status=?, available_at=?, updated_at=?, commit_sha=coalesce(?, commit_sha), last_error=?
        where id=?
        """)) {
      statement.setString(1, status);
      statement.setString(2, availableAt);
      statement.setString(3, Instant.now().toString());
      statement.setString(4, commitSha);
      statement.setString(5, error == null ? null : error.substring(0, Math.min(1000, error.length())));
      statement.setLong(6, id);
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("无法更新博客发布状态", ex);
    }
  }

  private void ensureSchema() {
    try {
      Path path = Path.of(properties.dbPath());
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      try (Connection connection = connect(); Statement statement = connection.createStatement()) {
        statement.execute("pragma journal_mode=WAL");
        statement.execute("pragma busy_timeout=5000");
        statement.execute("""
            create table if not exists publication_outbox (
              id integer primary key autoincrement,
              idempotency_key text not null unique,
              kind text not null,
              payload text not null,
              status text not null,
              attempts integer not null default 0,
              available_at text not null,
              created_at text not null,
              updated_at text not null,
              commit_sha text,
              last_error text
            )
            """);
        statement.execute("create index if not exists publication_outbox_due on publication_outbox(status, available_at, created_at)");
      }
    } catch (Exception ex) {
      throw new IllegalStateException("无法初始化博客发布队列", ex);
    }
  }

  private Connection connect() throws Exception {
    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + properties.dbPath());
    try (Statement statement = connection.createStatement()) {
      statement.execute("pragma busy_timeout=5000");
    }
    return connection;
  }

  public record PublicationItem(long id, String idempotencyKey, PublicationKind kind, String payload, int attempts) {
  }
}
