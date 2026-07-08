package cn.yinsheng.blog.rag.indexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IndexStatusRepository {

  public Map<String, StatusRow> load(String dbPath) {
    ensureSchema(dbPath);
    Map<String, StatusRow> rows = new LinkedHashMap<>();
    try (Connection connection = connect(dbPath);
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery("select slug, title, content_hash, updated_at, indexed_at, chunk_count from blog_index_status")) {
      while (resultSet.next()) {
        rows.put(resultSet.getString("slug"), new StatusRow(
            resultSet.getString("slug"),
            resultSet.getString("title"),
            resultSet.getString("content_hash"),
            resultSet.getString("updated_at"),
            resultSet.getString("indexed_at"),
            resultSet.getInt("chunk_count")
        ));
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to load index status", ex);
    }
    return rows;
  }

  public void upsert(String dbPath, BlogPost post, int chunkCount) {
    ensureSchema(dbPath);
    String sql = """
        insert into blog_index_status(slug, title, content_hash, updated_at, indexed_at, chunk_count)
        values(?, ?, ?, ?, ?, ?)
        on conflict(slug) do update set
          title = excluded.title,
          content_hash = excluded.content_hash,
          updated_at = excluded.updated_at,
          indexed_at = excluded.indexed_at,
          chunk_count = excluded.chunk_count
        """;
    try (Connection connection = connect(dbPath);
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, post.slug());
      statement.setString(2, post.title());
      statement.setString(3, post.contentHash());
      statement.setString(4, post.updatedAt());
      statement.setString(5, Instant.now().toString());
      statement.setInt(6, chunkCount);
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to update index status for " + post.slug(), ex);
    }
  }

  public void delete(String dbPath, String slug) {
    ensureSchema(dbPath);
    try (Connection connection = connect(dbPath);
         PreparedStatement statement = connection.prepareStatement("delete from blog_index_status where slug = ?")) {
      statement.setString(1, slug);
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to delete index status for " + slug, ex);
    }
  }

  private void ensureSchema(String dbPath) {
    try {
      Path path = Path.of(dbPath);
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      try (Connection connection = connect(dbPath);
           Statement statement = connection.createStatement()) {
        statement.executeUpdate("""
            create table if not exists blog_index_status (
              slug text primary key,
              title text,
              content_hash text,
              updated_at text,
              indexed_at text,
              chunk_count integer
            )
            """);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to ensure SQLite index schema", ex);
    }
  }

  private Connection connect(String dbPath) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
  }

  public record StatusRow(
      String slug,
      String title,
      String contentHash,
      String updatedAt,
      String indexedAt,
      int chunkCount
  ) {
  }
}
