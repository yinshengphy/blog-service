package cn.yinsheng.blog.service.tool;

import static org.assertj.core.api.Assertions.assertThat;

import cn.yinsheng.blog.service.BlogServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = BlogServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PublicToolSetTest {
  @Autowired
  private ToolRegistry registry;

  @Test
  void shouldExposeOnlyReadOnlyKnowledgeAndWeatherTools() {
    assertThat(registry.definitions()).extracting(ToolDefinition::name)
        .containsExactlyInAnyOrder("blog_qa", "blog_summary", "weather", "search_posts",
            "read_post_outline", "read_section", "read_post", "get_site_profile");
  }
}
