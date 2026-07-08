package cn.yinsheng.blog.rag.model;

import java.util.List;

public record RetrievedChunk(
    double score,
    String chunkId,
    String slug,
    String title,
    String section,
    String url,
    String content,
    List<String> tags,
    String updatedAt
) {
}
