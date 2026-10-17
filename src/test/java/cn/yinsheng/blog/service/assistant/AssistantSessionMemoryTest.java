package cn.yinsheng.blog.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssistantSessionMemoryTest {
  @Test
  void shouldPreserveArticleReferencesAcrossTurnsAndClearThem() {
    var memory = new AssistantSessionMemory(new cn.yinsheng.blog.service.config.AssistantProperties());
    memory.remember("refs", "最新文章", "第一篇和第二篇");
    var posts = java.util.List.of(java.util.Map.of("slug", "a"), java.util.Map.of("slug", "b"));
    memory.rememberContext("refs", null, java.util.List.of(), java.util.Map.of("recentPosts", posts, "slug", "b"));
    memory.remember("refs", "第二篇呢", "文章 b");
    assertThat(memory.context("refs")).containsEntry("resolvedSlug", "b").containsEntry("recentPosts", posts);
    memory.clear("refs");
    assertThat(memory.context("refs")).isEmpty();
  }
  @Test
  void shouldKeepOnlyEphemeralRecentMessages() {
    AssistantSessionMemory memory = new AssistantSessionMemory(new cn.yinsheng.blog.service.config.AssistantProperties());
    memory.remember("s1", "question", "answer");

    assertThat(memory.history("s1")).hasSize(2);
    memory.clear("s1");
    assertThat(memory.history("s1")).isEmpty();
  }
}
