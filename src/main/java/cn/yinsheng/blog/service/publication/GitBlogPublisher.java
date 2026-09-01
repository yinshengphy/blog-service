package cn.yinsheng.blog.service.publication;

import cn.yinsheng.blog.service.config.PublicationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GitBlogPublisher {
  private final PublicationProperties properties;
  private final ObjectMapper objectMapper;
  private final BlogContentMaterializer materializer;

  public GitBlogPublisher(
      PublicationProperties properties,
      ObjectMapper objectMapper,
      BlogContentMaterializer materializer
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.materializer = materializer;
  }

  public String publish(PublicationStore.PublicationItem item) throws Exception {
    validateRuntimeConfiguration();
    Path workspace = Files.createTempDirectory("blog-publication-");
    try {
      cloneRepository(workspace);
      switch (item.kind()) {
        case POST -> materializer.writePost(workspace, objectMapper.readValue(item.payload(), PostPublicationRequest.class));
        case BREAKING_EVENT -> materializer.upsertBreakingEvent(
            workspace, objectMapper.readValue(item.payload(), BreakingEventPublicationRequest.class));
      }
      if (run(workspace, List.of("git", "status", "--porcelain")).isBlank()) {
        return run(workspace, List.of("git", "rev-parse", "HEAD")).trim();
      }
      run(workspace, List.of("git", "add", "src/content/posts", "src/data/breaking-news.json"));
      run(workspace, List.of("git", "config", "user.name", properties.authorName()));
      run(workspace, List.of("git", "config", "user.email", properties.authorEmail()));
      run(workspace, List.of("git", "commit", "-m", commitMessage(item)));
      run(workspace, List.of("git", "push", "origin", "HEAD:" + properties.branch()));
      return run(workspace, List.of("git", "rev-parse", "HEAD")).trim();
    } finally {
      deleteWorkspace(workspace);
    }
  }

  private void cloneRepository(Path workspace) throws Exception {
    Path parent = workspace.getParent();
    if (parent == null) {
      throw new IllegalStateException("无法确定发布工作目录");
    }
    Files.deleteIfExists(workspace);
    run(parent, List.of(
        "git", "clone", "--depth", "1", "--branch", properties.branch(), properties.repoUri(), workspace.toString()
    ));
  }

  private String commitMessage(PublicationStore.PublicationItem item) {
    return switch (item.kind()) {
      case POST -> "content: 发布 " + item.idempotencyKey();
      case BREAKING_EVENT -> "content: 更新三日突发 " + item.idempotencyKey();
    };
  }

  private String run(Path directory, List<String> command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.put("GIT_TERMINAL_PROMPT", "0");
    environment.put(
        "GIT_SSH_COMMAND",
        "ssh -i " + properties.sshKeyPath()
            + " -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=" + properties.knownHostsPath()
    );
    Process process = builder.start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    process.getInputStream().transferTo(output);
    int exitCode = process.waitFor();
    String text = output.toString(StandardCharsets.UTF_8);
    if (exitCode != 0) {
      throw new IOException("Git 命令失败（" + String.join(" ", command.subList(0, Math.min(3, command.size()))) + "）：" + text.strip());
    }
    return text;
  }

  private void validateRuntimeConfiguration() {
    if (properties.repoUri() == null || properties.repoUri().isBlank()
        || properties.branch() == null || properties.branch().isBlank()) {
      throw new IllegalStateException("博客发布仓库配置不完整");
    }
    if (!Files.isRegularFile(Path.of(properties.sshKeyPath())) || !Files.isRegularFile(Path.of(properties.knownHostsPath()))) {
      throw new IllegalStateException("博客发布 SSH 凭据不可用");
    }
  }

  private void deleteWorkspace(Path workspace) {
    if (workspace == null || !Files.exists(workspace)) {
      return;
    }
    try (var files = Files.walk(workspace)) {
      files.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // The next publisher run uses a different temporary directory.
        }
      });
    } catch (IOException ignored) {
      // The OS temp-directory cleaner can safely remove an abandoned checkout.
    }
  }
}
