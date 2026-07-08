package cn.yinsheng.blog.rag.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
    @NotBlank(message = "question 不能为空") String question,
    String sessionId
) {
}
