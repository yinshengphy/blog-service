package cn.yinsheng.blog.service.publication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DailyNewsSectionPublicationRequest(
    @NotBlank @Size(max = 160) String idempotencyKey,
    @NotBlank @Pattern(regexp = "daily-news-[0-9]{8}") String slug,
    @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String date,
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sectionHash,
    @NotBlank @Size(max = 20000) String body
) {
}
