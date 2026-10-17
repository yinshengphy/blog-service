package cn.yinsheng.blog.service.assistant;

import cn.yinsheng.blog.service.compute.AiComputeClient;
import cn.yinsheng.blog.service.model.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ModelRoutePlanner {
  private final AiComputeClient aiComputeClient;
  private final ObjectMapper objectMapper;
  private final String prompt;

  public ModelRoutePlanner(AiComputeClient aiComputeClient, ObjectMapper objectMapper) {
    this.aiComputeClient = aiComputeClient;
    this.objectMapper = objectMapper;
    this.prompt = loadPrompt();
  }

  public RoutePlan plan(ChatRequest request) {
    return plan(request, List.of());
  }

  public RoutePlan plan(ChatRequest request, List<Map<String, Object>> history) {
    try {
      String input = objectMapper.writeValueAsString(Map.of(
          "question", request.question(),
          "currentPage", request.pageContext() == null ? Map.of() : request.pageContext(),
          "recentConversation", history == null ? List.of() : history
      ));
      String output = aiComputeClient.classify(prompt, input).trim();
      if (output.startsWith("```")) {
        output = output.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      }
      JsonNode node = objectMapper.readTree(output);
      Route route = Route.valueOf(node.path("route").asText("UNKNOWN").toUpperCase());
      if (route == Route.BLOG_CURRENT_QA
          && (request.pageContext() == null || !request.pageContext().isBlogPost())) {
        route = Route.BLOG_SITE_QA;
      }
      Map<String, Object> arguments = new LinkedHashMap<>();
      copyText(node, arguments, "query");
      copyText(node, arguments, "task");
      copyText(node, arguments, "scope");
      copyText(node, arguments, "target");
      copyText(node, arguments, "focus");
      copyText(node, arguments, "city");
      for (String field : List.of("category", "tag", "date_from", "date_to", "sort", "section")) copyText(node, arguments, field);
      for (String field : List.of("offset", "limit", "max_chars")) if (node.path(field).isIntegralNumber()) arguments.put(field, node.path(field).asInt());
      applyBlogDefaults(route, arguments, request);
      if (route == Route.WEATHER) arguments.putIfAbsent("city", request.question().trim());
      if (route == Route.BLOG_SUMMARY && !arguments.containsKey("target")
          && (request.pageContext() == null || !request.pageContext().isBlogPost())) {
        arguments.put("target", request.question().trim());
      }
      return new RoutePlan(route, Map.copyOf(arguments));
    } catch (Exception ex) {
      return new RoutePlan(Route.UNKNOWN, Map.of());
    }
  }

  private void applyBlogDefaults(Route route, Map<String, Object> arguments, ChatRequest request) {
    switch (route) {
      case BLOG_CURRENT_QA -> {
        arguments.put("task", "ANSWER");
        arguments.put("scope", "CURRENT_POST");
      }
      case BLOG_SITE_QA -> {
        arguments.put("task", "ANSWER");
        arguments.put("scope", "ALL_POSTS");
      }
      case BLOG_LOCATE -> {
        arguments.put("task", "LOCATE");
        arguments.putIfAbsent("scope", request.pageContext() != null && request.pageContext().isBlogPost()
            ? "CURRENT_POST" : "ALL_POSTS");
      }
      case BLOG_SEARCH -> {
        arguments.put("task", "SEARCH");
        arguments.put("scope", "ALL_POSTS");
      }
      case BLOG_RECOMMEND -> {
        arguments.put("task", "RECOMMEND");
        arguments.put("scope", "ALL_POSTS");
      }
      default -> {
      }
    }
  }

  private void copyText(JsonNode node, Map<String, Object> arguments, String name) {
    String value = node.path(name).asText("").trim();
    if (!value.isBlank()) arguments.put(name, value);
  }

  private String loadPrompt() {
    try {
      return new ClassPathResource("prompts/route-planner.md").getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to load route planner prompt", ex);
    }
  }

  public enum Route {
    DIRECT_PERSONA,
    CAPABILITY,
    DIRECT_GENERAL,
    BLOG_CURRENT_QA,
    BLOG_SITE_QA,
    BLOG_LOCATE,
    BLOG_SEARCH,
    BLOG_CATALOG,
    BLOG_OUTLINE,
    BLOG_SECTION,
    BLOG_READ,
    SITE_PROFILE,
    BLOG_RECOMMEND,
    BLOG_SUMMARY,
    WEATHER,
    CLARIFICATION,
    UNSUPPORTED,
    UNKNOWN
  }

  public record RoutePlan(Route route, Map<String, Object> arguments) {
    public String toolName() {
      return switch (route) {
        case BLOG_CURRENT_QA, BLOG_SITE_QA, BLOG_LOCATE, BLOG_SEARCH, BLOG_RECOMMEND -> "blog_qa";
        case BLOG_SUMMARY -> "blog_summary";
        case BLOG_CATALOG -> "search_posts";
        case BLOG_OUTLINE -> "read_post_outline";
        case BLOG_SECTION -> "read_section";
        case BLOG_READ -> "read_post";
        case SITE_PROFILE -> "get_site_profile";
        case WEATHER -> "weather";
        default -> "";
      };
    }
  }
}
