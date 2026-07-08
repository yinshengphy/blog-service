package cn.yinsheng.blog.rag.model;

public record Citation(
    String title,
    String section,
    String url,
    String snippet
) {
}
