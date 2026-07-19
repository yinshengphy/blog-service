package cn.yinsheng.blog.service.rag;

import cn.yinsheng.blog.service.model.RelatedPost;
import cn.yinsheng.blog.service.model.RetrievedChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RelatedPostBuilder {
  public List<RelatedPost> build(List<RetrievedChunk> chunks) {
    return build(chunks, null);
  }

  public List<RelatedPost> build(List<RetrievedChunk> chunks, String currentSlug) {
    if (chunks == null || chunks.isEmpty()) {
      return List.of();
    }
    Map<String, RelatedPost> posts = new LinkedHashMap<>();
    for (RetrievedChunk chunk : chunks) {
      if (chunk.slug() == null || chunk.slug().isBlank()) {
        continue;
      }
      if (currentSlug != null && !currentSlug.isBlank() && chunk.slug().equalsIgnoreCase(currentSlug.trim())) {
        continue;
      }
      if (isEssay(chunk)) {
        continue;
      }
      posts.putIfAbsent(chunk.slug(), new RelatedPost(chunk.title(), "/" + chunk.slug() + "/"));
    }
    return new ArrayList<>(posts.values());
  }

  private boolean isEssay(RetrievedChunk chunk) {
    if (chunk.slug() != null && chunk.slug().toLowerCase(java.util.Locale.ROOT).startsWith("essay-")) {
      return true;
    }
    if (chunk.categories() != null) {
      for (String category : chunk.categories()) {
        if ("随笔".equalsIgnoreCase(category) || "生活碎念".equalsIgnoreCase(category)) {
          return true;
        }
      }
    }
    if (chunk.tags() != null) {
      for (String tag : chunk.tags()) {
        if ("随笔".equalsIgnoreCase(tag) || "生活碎念".equalsIgnoreCase(tag)) {
          return true;
        }
      }
    }
    return false;
  }
}
