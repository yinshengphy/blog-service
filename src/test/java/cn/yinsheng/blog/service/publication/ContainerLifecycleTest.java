package cn.yinsheng.blog.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContainerLifecycleTest {
  @ParameterizedTest
  @ValueSource(strings = {"Dockerfile", "src/main/docker/Dockerfile"})
  void runtimeMustUseInitToReapOrphanProcesses(String dockerfile) throws Exception {
    String source = Files.readString(Path.of(dockerfile));
    String runtime = source.substring(source.lastIndexOf("FROM "));
    assertThat(runtime).contains("apt-get install", "tini");
    String entrypoint = runtime.lines()
        .filter(line -> line.startsWith("ENTRYPOINT "))
        .findFirst().orElseThrow().substring("ENTRYPOINT ".length());
    List<?> command = new ObjectMapper().readValue(entrypoint, List.class);
    assertThat(command.subList(0, 4))
        .isEqualTo(List.of("/usr/bin/tini", "--", "java", "-jar"));
  }
}
