package cn.yinsheng.blog.rag;

import cn.yinsheng.blog.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class BlogRagApplication {

  public static void main(String[] args) {
    SpringApplication.run(BlogRagApplication.class, args);
  }
}
