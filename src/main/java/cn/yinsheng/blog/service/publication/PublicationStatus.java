package cn.yinsheng.blog.service.publication;

public record PublicationStatus(
    long id,
    String idempotencyKey,
    String kind,
    String status,
    int attempts,
    String updatedAt,
    String commitSha,
    String lastError
) {
}
