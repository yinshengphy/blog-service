package cn.yinsheng.blog.rag.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class MarkdownPostReader {

  public List<BlogPost> readPosts(Path contentDir) throws IOException {
    if (!Files.exists(contentDir)) {
      return List.of();
    }
    try (var files = Files.walk(contentDir)) {
      List<Path> markdownFiles = files
          .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".md"))
          .sorted(Comparator.naturalOrder())
          .toList();
      List<BlogPost> posts = new ArrayList<>();
      for (Path file : markdownFiles) {
        posts.add(readPost(contentDir, file));
      }
      return posts;
    }
  }

  private BlogPost readPost(Path contentDir, Path file) throws IOException {
    String raw = Files.readString(file, StandardCharsets.UTF_8);
    FrontMatter frontMatter = splitFrontMatter(raw);
    Map<String, Object> data = frontMatter.data();
    String slug = slugFromPath(contentDir, file);
    String title = stringValue(data.get("title"), slug);
    String date = stringValue(data.get("date"), "");
    String updatedAt = stringValue(data.get("updated_at"), "");
    if (updatedAt.isBlank()) {
      updatedAt = Files.getLastModifiedTime(file).toInstant().toString();
    }
    return new BlogPost(
        slug,
        title,
        date,
        updatedAt,
        listValue(data.get("tags")),
        stringValue(data.get("description"), ""),
        frontMatter.body().trim(),
        sha256(raw)
    );
  }

  private FrontMatter splitFrontMatter(String raw) {
    if (!raw.startsWith("---")) {
      return new FrontMatter(Map.of(), raw);
    }
    int end = raw.indexOf("\n---", 3);
    if (end < 0) {
      return new FrontMatter(Map.of(), raw);
    }
    String yamlText = raw.substring(3, end).trim();
    String body = raw.substring(end + 4);
    Object parsed = new Yaml().load(yamlText);
    if (parsed instanceof Map<?, ?> map) {
      Map<String, Object> data = new LinkedHashMap<>();
      map.forEach((key, value) -> data.put(String.valueOf(key), value));
      return new FrontMatter(data, body);
    }
    return new FrontMatter(Map.of(), body);
  }

  private String slugFromPath(Path contentDir, Path file) {
    Path relative = contentDir.relativize(file);
    String name = relative.toString().replace('\\', '/');
    return name.substring(0, name.length() - 3);
  }

  private String stringValue(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value).replaceAll("^\"|\"$", "");
  }

  private List<String> listValue(Object value) {
    if (value instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of();
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to calculate SHA-256", ex);
    }
  }

  private record FrontMatter(Map<String, Object> data, String body) {
  }
}
