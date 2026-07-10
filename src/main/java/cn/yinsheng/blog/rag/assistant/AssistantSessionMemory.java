package cn.yinsheng.blog.rag.assistant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AssistantSessionMemory {
  private static final int MAX_MESSAGES = 4;
  private static final int MAX_MESSAGE_CHARS = 1000;
  private static final long TTL_MINUTES = 20;
  private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

  public List<Map<String, Object>> history(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) return List.of();
    SessionState state = sessions.get(sessionId);
    if (state == null || state.expiresAt().isBefore(Instant.now())) {
      sessions.remove(sessionId);
      return List.of();
    }
    return List.copyOf(state.messages());
  }

  public void remember(String sessionId, String userMessage, String assistantMessage) {
    if (sessionId == null || sessionId.isBlank() || assistantMessage == null || assistantMessage.isBlank()) return;
    List<Map<String, Object>> messages = new ArrayList<>(history(sessionId));
    messages.add(Map.of("role", "user", "content", compact(userMessage)));
    messages.add(Map.of("role", "assistant", "content", compact(assistantMessage)));
    if (messages.size() > MAX_MESSAGES) {
      messages = new ArrayList<>(messages.subList(messages.size() - MAX_MESSAGES, messages.size()));
    }
    sessions.put(sessionId, new SessionState(List.copyOf(messages), Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES)));
  }

  public void clear(String sessionId) {
    if (sessionId != null) sessions.remove(sessionId);
  }

  private String compact(String value) {
    if (value == null) return "";
    return value.length() <= MAX_MESSAGE_CHARS ? value : value.substring(0, MAX_MESSAGE_CHARS);
  }

  private record SessionState(List<Map<String, Object>> messages, Instant expiresAt) {
  }
}
