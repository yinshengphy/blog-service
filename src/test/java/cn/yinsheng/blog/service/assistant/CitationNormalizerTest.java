package cn.yinsheng.blog.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.yinsheng.blog.service.model.Citation;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationNormalizerTest {
  private final CitationNormalizer normalizer = new CitationNormalizer();

  @Test
  void shouldRenumberCitationsByFirstAppearance() {
    List<Citation> citations = List.of(
        citation("第一节", "/post/#one"),
        citation("第二节", "/post/#two"),
        citation("第三节", "/post/#three")
    );

    CitationNormalizer.Result result = normalizer.normalize("先引用第三节 [3]，再引用第一节 [1]。", citations);

    assertThat(result.answer()).isEqualTo("先引用第三节 [1]，再引用第一节 [2]。");
    assertThat(result.citations()).extracting(Citation::section).containsExactly("第三节", "第一节");
  }

  @Test
  void shouldNotInventCitationWhenModelOmitsMarker() {
    CitationNormalizer.Result result = normalizer.normalize(
        "这是基于博客证据的回答。",
        List.of(citation("正文", "/post/#body"))
    );

    assertThat(result.answer()).doesNotContain("[1]");
    assertThat(result.citations()).isEmpty();
  }

  private Citation citation(String section, String url) {
    return new Citation("文章", section, url, "片段");
  }
}
