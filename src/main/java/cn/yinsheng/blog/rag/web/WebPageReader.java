package cn.yinsheng.blog.rag.web;

import cn.yinsheng.blog.rag.config.WebSearchProperties;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Function;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebPageReader {
  private final WebSearchProperties properties;
  private final Function<URI, Connection> connectionFactory;

  @Autowired
  public WebPageReader(WebSearchProperties properties) {
    this(properties, uri -> Jsoup.connect(uri.toString()));
  }

  WebPageReader(WebSearchProperties properties, Function<URI, Connection> connectionFactory) {
    this.properties = properties;
    this.connectionFactory = connectionFactory;
  }

  public String read(String rawUrl) {
    try {
      URI uri = URI.create(rawUrl);
      for (int redirects = 0; redirects < 4; redirects++) {
        validate(uri);
        Connection.Response response = connectionFactory.apply(uri)
            .userAgent("yinsheng-site-assistant/1.0")
            .timeout(Math.max(1, properties.getTimeoutSeconds()) * 1000)
            .maxBodySize(properties.getMaxPageBytes())
            .followRedirects(false)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .execute();
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
          String location = response.header("location");
          if (location == null || location.isBlank()) return "";
          uri = uri.resolve(location);
          continue;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
        String contentType = String.valueOf(response.contentType()).toLowerCase(Locale.ROOT);
        if (!contentType.contains("text/html") && !contentType.contains("text/plain")) return "";
        byte[] bytes = response.bodyAsBytes();
        if (contentType.contains("text/plain")) return compact(new String(bytes, StandardCharsets.UTF_8));
        Document document = Jsoup.parse(new ByteArrayInputStream(bytes), null, uri.toString());
        document.select("script,style,noscript,nav,footer,header,aside,form").remove();
        return compact(document.body().text());
      }
    } catch (Exception ignored) {
      return "";
    }
    return "";
  }

  private void validate(URI uri) throws Exception {
    if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
      throw new IllegalArgumentException("Unsupported URL scheme");
    }
    if (uri.getHost() == null) throw new IllegalArgumentException("URL host is missing");
    for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
      if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
          || address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocalV6(address)) {
        throw new IllegalArgumentException("Private network addresses are not allowed");
      }
    }
  }

  private boolean isUniqueLocalV6(InetAddress address) {
    byte[] bytes = address.getAddress();
    return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
  }

  private String compact(String value) {
    String compact = value.replaceAll("\\s+", " ").trim();
    return compact.length() <= 5000 ? compact : compact.substring(0, 5000);
  }
}
