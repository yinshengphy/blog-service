package cn.yinsheng.blog.service.rag;

import cn.yinsheng.blog.service.config.RagProperties;
import cn.yinsheng.blog.service.indexer.BlogPost;
import cn.yinsheng.blog.service.indexer.MarkdownPostReader;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/** 从公开正文目录读取文章，目录查询不依赖向量分块的召回结果。 */
@Service
public class BlogLibrary {
  private final MarkdownPostReader reader;
  private final RagProperties properties;
  private volatile List<BlogPost> cached = List.of();
  private volatile long expiresAt;

  public BlogLibrary(MarkdownPostReader reader, RagProperties properties) {
    this.reader = reader; this.properties = properties;
  }

  public synchronized List<BlogPost> posts() {
    if (System.currentTimeMillis() >= expiresAt) {
      try { cached = List.copyOf(reader.readPosts(Path.of(properties.contentDir()))); }
      catch (java.io.IOException ex) { throw new IllegalStateException("公开文章目录暂时无法读取"); }
      expiresAt = System.currentTimeMillis() + 10_000;
    }
    return cached;
  }

  public List<BlogPost> resolve(String target) {
    String query = normalize(target);
    if (query.isBlank()) return List.of();
    List<BlogPost> exact = posts().stream().filter(p -> normalize(p.slug()).equals(query)
        || normalize(p.title()).equals(query)).toList();
    if (!exact.isEmpty()) return exact;
    return posts().stream().filter(p -> normalize(p.title()).contains(query)
        || query.contains(normalize(p.title())) || normalize(p.slug()).equals(query.replace("posts", ""))).toList();
  }

  public static String normalize(String value) {
    return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s/\\-_'\"，。！？：；（）()]+", "");
  }
}
