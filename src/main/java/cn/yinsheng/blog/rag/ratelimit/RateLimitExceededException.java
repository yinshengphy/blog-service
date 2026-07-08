package cn.yinsheng.blog.rag.ratelimit;

public class RateLimitExceededException extends RuntimeException {
  public RateLimitExceededException(String message) {
    super(message);
  }
}
