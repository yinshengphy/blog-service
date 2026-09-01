package cn.yinsheng.blog.service;

import cn.yinsheng.blog.service.config.AssistantProperties;
import cn.yinsheng.blog.service.config.PublicationProperties;
import cn.yinsheng.blog.service.config.RagProperties;
import cn.yinsheng.blog.service.config.WeatherProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    RagProperties.class,
    AssistantProperties.class,
    WeatherProperties.class,
    PublicationProperties.class
})
public class BlogServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(BlogServiceApplication.class, args);
  }
}
