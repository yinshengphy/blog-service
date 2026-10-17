package cn.yinsheng.blog.service.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StreamToolCallsTest {
  @Test
  void mergesInterleavedArgumentsBeforeParsing() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    StreamToolCalls calls = new StreamToolCalls(mapper);
    calls.accept(mapper.readTree("""
        [{"index":0,"id":"call1","function":{"name":"blog_search","arguments":"{\\\"query\\\":"}},
         {"index":1,"id":"call2","function":{"name":"weather","arguments":"{\\\"city\\\":"}}]
        """));
    calls.accept(mapper.readTree("""
        [{"index":1,"function":{"arguments":"\\\"上海\\\"}"}},
         {"index":0,"function":{"arguments":"\\\"RAG\\\"}"}}]
        """));
    assertThat(calls.complete()).hasSize(2);
    assertThat(calls.complete().get(0).path("id").asText()).isEqualTo("call1");
    assertThat(mapper.readTree(calls.complete().get(0).path("function").path("arguments").asText())
        .path("query").asText()).isEqualTo("RAG");
    assertThat(mapper.readTree(calls.complete().get(1).path("function").path("arguments").asText())
        .path("city").asText()).isEqualTo("上海");
  }
}
