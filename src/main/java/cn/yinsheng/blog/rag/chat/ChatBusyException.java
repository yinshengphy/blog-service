package cn.yinsheng.blog.rag.chat;

public class ChatBusyException extends RuntimeException {
  public ChatBusyException(String message) {
    super(message);
  }
}
