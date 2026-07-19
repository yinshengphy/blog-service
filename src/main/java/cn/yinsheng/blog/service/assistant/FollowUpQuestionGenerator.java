package cn.yinsheng.blog.service.assistant;

import cn.yinsheng.blog.service.config.AssistantProperties;
import cn.yinsheng.blog.service.model.Citation;
import cn.yinsheng.blog.service.model.PageContext;
import cn.yinsheng.blog.service.model.RelatedPost;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FollowUpQuestionGenerator {
  private final AssistantProperties properties;

  public FollowUpQuestionGenerator(AssistantProperties properties) {
    this.properties = properties;
  }

  public List<String> generate(
      ModelRoutePlanner.Route route,
      PageContext pageContext,
      List<Citation> citations,
      List<RelatedPost> relatedPosts
  ) {
    Set<String> values = new LinkedHashSet<>();
    if (citations != null) {
      citations.stream()
          .map(Citation::section)
          .filter(section -> section != null && !section.isBlank())
          .limit(2)
          .forEach(section -> values.add("展开讲讲「" + compact(section, 12) + "」"));
    }
    if (relatedPosts != null && !relatedPosts.isEmpty()) {
      values.add("还有哪些相关文章值得一起看？");
    }

    switch (route) {
      case BLOG_CURRENT_QA, BLOG_LOCATE -> {
        values.add("这篇文章的核心结论是什么？");
        values.add("这个结论在文章的哪一节？");
      }
      case BLOG_SITE_QA, BLOG_SEARCH, BLOG_RECOMMEND -> {
        values.add("这些文章分别从什么角度讨论这个主题？");
        values.add("推荐一篇最适合先读的文章");
      }
      case BLOG_SUMMARY -> {
        values.add("文中最值得实践的建议是什么？");
        values.add("哪些章节最值得重点阅读？");
      }
      case DIRECT_GENERAL -> {
        values.add("能举一个具体例子吗？");
        values.add("这个结论有哪些适用边界？");
      }
      case DIRECT_PERSONA, CAPABILITY -> {
        values.add("你能怎么帮助我阅读这个博客？");
        values.add("推荐几篇适合先看的文章");
      }
      default -> {
      }
    }
    if (pageContext != null && pageContext.isBlogPost()) {
      values.add("这篇文章还有哪些相关内容？");
    }
    int limit = Math.max(0, Math.min(properties.getMaxSuggestedQuestions(), 3));
    return new ArrayList<>(values).stream().limit(limit).toList();
  }

  private String compact(String value, int maxChars) {
    String text = value.trim();
    return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
  }
}
