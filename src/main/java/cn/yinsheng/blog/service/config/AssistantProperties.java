package cn.yinsheng.blog.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {
  private int maxToolCallsPerRequest = 5;
  private int sessionTtlMinutes = 30;
  private int sessionMaxMessages = 8;
  private int sessionMaxMessageChars = 1000;
  private int sessionMaxEntries = 500;
  private int maxSuggestedQuestions = 3;

  public int getMaxToolCallsPerRequest() {
    return maxToolCallsPerRequest;
  }

  public void setMaxToolCallsPerRequest(int maxToolCallsPerRequest) {
    this.maxToolCallsPerRequest = maxToolCallsPerRequest;
  }

  public int getSessionTtlMinutes() {
    return sessionTtlMinutes;
  }

  public void setSessionTtlMinutes(int sessionTtlMinutes) {
    this.sessionTtlMinutes = sessionTtlMinutes;
  }

  public int getSessionMaxMessages() {
    return sessionMaxMessages;
  }

  public void setSessionMaxMessages(int sessionMaxMessages) {
    this.sessionMaxMessages = sessionMaxMessages;
  }

  public int getSessionMaxMessageChars() {
    return sessionMaxMessageChars;
  }

  public void setSessionMaxMessageChars(int sessionMaxMessageChars) {
    this.sessionMaxMessageChars = sessionMaxMessageChars;
  }

  public int getSessionMaxEntries() {
    return sessionMaxEntries;
  }

  public void setSessionMaxEntries(int sessionMaxEntries) {
    this.sessionMaxEntries = sessionMaxEntries;
  }

  public int getMaxSuggestedQuestions() {
    return maxSuggestedQuestions;
  }

  public void setMaxSuggestedQuestions(int maxSuggestedQuestions) {
    this.maxSuggestedQuestions = maxSuggestedQuestions;
  }
}
