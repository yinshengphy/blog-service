package cn.yinsheng.blog.rag.model;

import java.util.List;

public record ChunkRecord(
    String chunkId,
    String slug,
    String title,
    String section,
    String headingPath,
    String url,
    String content,
    String contentHash,
    String chunkHash,
    List<String> tags,
    String date,
    String updatedAt
) {
  public String retrievalText() {
    return """
        文章标题：%s
        小节路径：%s
        标签：%s
        正文片段：
        %s
        """.formatted(title, headingPath, String.join(", ", tags), content);
  }
}
