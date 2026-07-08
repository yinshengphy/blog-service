package cn.yinsheng.blog.rag.model;

import java.util.List;

public record ChatResponse(
    String answer,
    List<Citation> citations,
    List<RelatedPost> relatedPosts
) {
}
