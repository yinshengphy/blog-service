package cn.yinsheng.blog.rag.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

  @Bean
  RestClient restClient(RagProperties properties) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.requestTimeout());
    requestFactory.setReadTimeout(properties.requestTimeout());
    return RestClient.builder()
        .requestFactory(requestFactory)
        .build();
  }
}
