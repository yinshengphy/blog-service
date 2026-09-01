package cn.yinsheng.blog.service.publication;

public record PublicationReceipt(
    long id,
    String status,
    boolean duplicate
) {
}
