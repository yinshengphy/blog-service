package cn.yinsheng.blog.rag.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yinsheng.blog.rag.config.WebSearchProperties;
import java.net.URI;
import org.jsoup.Connection;
import org.junit.jupiter.api.Test;

class WebPageReaderTest {
  @Test
  void limitsReadTimeAndPageSize() throws Exception {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setTimeoutSeconds(3);
    properties.setMaxPageBytes(4096);
    Connection connection = mock(Connection.class);
    Connection.Response response = mock(Connection.Response.class);
    when(connection.userAgent("yinsheng-site-assistant/1.0")).thenReturn(connection);
    when(connection.timeout(3000)).thenReturn(connection);
    when(connection.maxBodySize(4096)).thenReturn(connection);
    when(connection.followRedirects(false)).thenReturn(connection);
    when(connection.ignoreHttpErrors(true)).thenReturn(connection);
    when(connection.ignoreContentType(true)).thenReturn(connection);
    when(connection.execute()).thenReturn(response);
    when(response.statusCode()).thenReturn(200);
    when(response.contentType()).thenReturn("text/plain; charset=utf-8");
    when(response.bodyAsBytes()).thenReturn("  hello   world  ".getBytes());

    WebPageReader reader = new WebPageReader(properties, ignored -> connection);

    assertThat(reader.read("https://1.1.1.1/page")).isEqualTo("hello world");
    verify(connection).timeout(3000);
    verify(connection).maxBodySize(4096);
    verify(connection).followRedirects(false);
  }

  @Test
  void rejectsRedirectToPrivateNetwork() throws Exception {
    WebSearchProperties properties = new WebSearchProperties();
    Connection connection = mock(Connection.class);
    Connection.Response response = mock(Connection.Response.class);
    when(connection.userAgent("yinsheng-site-assistant/1.0")).thenReturn(connection);
    when(connection.timeout(10000)).thenReturn(connection);
    when(connection.maxBodySize(1_000_000)).thenReturn(connection);
    when(connection.followRedirects(false)).thenReturn(connection);
    when(connection.ignoreHttpErrors(true)).thenReturn(connection);
    when(connection.ignoreContentType(true)).thenReturn(connection);
    when(connection.execute()).thenReturn(response);
    when(response.statusCode()).thenReturn(302);
    when(response.header("location")).thenReturn("http://127.0.0.1/internal");

    WebPageReader reader = new WebPageReader(properties, uri -> {
      assertThat(uri).isEqualTo(URI.create("https://1.1.1.1/page"));
      return connection;
    });

    assertThat(reader.read("https://1.1.1.1/page")).isEmpty();
  }
}
