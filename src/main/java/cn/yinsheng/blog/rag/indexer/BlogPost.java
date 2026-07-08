package cn.yinsheng.blog.rag.indexer;

import java.util.List;

public record BlogPost(
    String slug,
    String title,
    String date,
    String updatedAt,
    List<String> tags,
    String description,
    String body,
    String contentHash
) {
}
