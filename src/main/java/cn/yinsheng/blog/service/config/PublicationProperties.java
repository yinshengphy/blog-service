package cn.yinsheng.blog.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "publication")
public record PublicationProperties(
    boolean enabled,
    boolean apiEnabled,
    boolean workerEnabled,
    String apiToken,
    String dbPath,
    String repoUri,
    String branch,
    String sshKeyPath,
    String knownHostsPath,
    String authorName,
    String authorEmail,
    long workerPollMs,
    int maxAttempts
) {
}
