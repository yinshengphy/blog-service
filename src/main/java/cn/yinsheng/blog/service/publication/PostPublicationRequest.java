package cn.yinsheng.blog.service.publication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PostPublicationRequest(
    @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9_.:@/-]+") String idempotencyKey,
    @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 120) String slug,
    @NotBlank @Size(max = 160) String title,
    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String date,
    @NotBlank @Size(max = 300) String description,
    @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 40) String> categories,
    @Size(max = 12) List<@NotBlank @Size(max = 40) String> tags,
    @NotBlank @Size(max = 60000) String body
) {
}
