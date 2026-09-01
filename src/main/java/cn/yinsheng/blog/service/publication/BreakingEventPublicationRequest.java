package cn.yinsheng.blog.service.publication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BreakingEventPublicationRequest(
    @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9_.:@/-]+") String idempotencyKey,
    @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9_.:@/-]+") String fingerprint,
    @NotBlank @Size(max = 220) String title,
    @NotBlank @Size(max = 600) String summary,
    @NotBlank @Size(max = 100) String source,
    @NotBlank @Size(max = 64) String discoveredAt,
    @NotBlank @Size(max = 40) String severity,
    @Size(max = 2000) String link
) {
}
