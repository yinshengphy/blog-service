package cn.yinsheng.blog.service.qdrant;

import cn.yinsheng.blog.service.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class QdrantPaginationTest {
  @Test void readsAllPagesInsteadOfSilentlyTruncating() {
    RestClient.Builder builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    RagProperties properties = mock(RagProperties.class);
    when(properties.qdrantUrl()).thenReturn("http://qdrant");
    when(properties.qdrantCollection()).thenReturn("blog_live");
    server.expect(requestTo("http://qdrant/collections/blog_live/points/scroll"))
        .andRespond(withSuccess("""
            {"result":{"points":[{"payload":{"slug":"a","title":"A","chunk_index":1}}],"next_page_offset":"next"}}
            """, MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://qdrant/collections/blog_live/points/scroll"))
        .andExpect(content().json("{\"offset\":\"next\"}"))
        .andRespond(withSuccess("""
            {"result":{"points":[{"payload":{"slug":"b","title":"B","chunk_index":1}}],"next_page_offset":null}}
            """, MediaType.APPLICATION_JSON));
    assertThat(new QdrantClient(builder.build(), properties).listPosts())
        .extracting(QdrantClient.PostEntry::slug).containsExactly("a", "b");
    server.verify();
  }

  @Test void dimensionMismatchDoesNotDeleteCollection() {
    RestClient.Builder builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    RagProperties properties = mock(RagProperties.class);
    when(properties.qdrantUrl()).thenReturn("http://qdrant");
    when(properties.qdrantCollection()).thenReturn("blog_live");
    server.expect(requestTo("http://qdrant/collections/blog_live"))
        .andRespond(withSuccess("{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024}}}}}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> new QdrantClient(builder.build(), properties).ensureCollection(768))
        .isInstanceOf(IllegalStateException.class);
    server.verify();
  }
}
