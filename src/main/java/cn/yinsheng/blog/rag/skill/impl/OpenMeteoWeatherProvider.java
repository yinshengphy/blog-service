package cn.yinsheng.blog.rag.skill.impl;

import cn.yinsheng.blog.rag.config.WeatherProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "weather", name = "provider", havingValue = "open-meteo", matchIfMissing = true)
public class OpenMeteoWeatherProvider implements WeatherProvider {
  private static final Map<String, CityLocation> KNOWN_CITY_LOCATIONS = Map.ofEntries(
      Map.entry("北京", new CityLocation("北京", 39.9042, 116.4074)),
      Map.entry("上海", new CityLocation("上海", 31.2222, 121.4581)),
      Map.entry("广州", new CityLocation("广州", 23.1291, 113.2644)),
      Map.entry("深圳", new CityLocation("深圳", 22.5431, 114.0579)),
      Map.entry("杭州", new CityLocation("杭州", 30.2741, 120.1551)),
      Map.entry("南京", new CityLocation("南京", 32.0603, 118.7969)),
      Map.entry("苏州", new CityLocation("苏州", 31.2989, 120.5853)),
      Map.entry("成都", new CityLocation("成都", 30.5728, 104.0668)),
      Map.entry("重庆", new CityLocation("重庆", 29.5630, 106.5516)),
      Map.entry("武汉", new CityLocation("武汉", 30.5928, 114.3055)),
      Map.entry("西安", new CityLocation("西安", 34.3416, 108.9398)),
      Map.entry("天津", new CityLocation("天津", 39.3434, 117.3616)),
      Map.entry("哈尔滨", new CityLocation("哈尔滨", 45.8038, 126.5349)),
      Map.entry("黑龙江", new CityLocation("黑龙江（哈尔滨）", 45.8038, 126.5349)),
      Map.entry("长沙", new CityLocation("长沙", 28.2282, 112.9388)),
      Map.entry("郑州", new CityLocation("郑州", 34.7466, 113.6254)),
      Map.entry("青岛", new CityLocation("青岛", 36.0671, 120.3826)),
      Map.entry("厦门", new CityLocation("厦门", 24.4798, 118.0894)),
      Map.entry("福州", new CityLocation("福州", 26.0745, 119.2965)),
      Map.entry("合肥", new CityLocation("合肥", 31.8206, 117.2272)),
      Map.entry("济南", new CityLocation("济南", 36.6512, 117.1201)),
      Map.entry("昆明", new CityLocation("昆明", 25.0389, 102.7183))
  );

  private final RestClient restClient;
  private final WeatherProperties properties;

  public OpenMeteoWeatherProvider(RestClient restClient, WeatherProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public WeatherReport currentWeather(String city) {
    CityLocation location = KNOWN_CITY_LOCATIONS.get(city);
    if (location == null) {
      location = KNOWN_CITY_LOCATIONS.entrySet().stream()
          .filter(entry -> city.contains(entry.getKey()))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(null);
    }
    if (location != null) {
      return forecast(location);
    }

    String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
    JsonNode geocoding = restClient.get()
        .uri(properties.getOpenMeteo().getGeocodingBaseUrl()
            + "/v1/search?name=" + encodedCity + "&count=1&language=zh&format=json")
        .retrieve()
        .body(JsonNode.class);
    JsonNode first = geocoding == null ? null : geocoding.path("results").path(0);
    if (first == null || first.isMissingNode()) {
      throw new IllegalArgumentException("找不到城市：" + city);
    }
    CityLocation resolvedLocation = new CityLocation(
        first.path("name").asText(city),
        first.path("latitude").asDouble(),
        first.path("longitude").asDouble()
    );
    return forecast(resolvedLocation);
  }

  private WeatherReport forecast(CityLocation location) {
    JsonNode forecast = restClient.get()
        .uri(properties.getOpenMeteo().getBaseUrl()
            + "/v1/forecast?latitude=" + location.latitude()
            + "&longitude=" + location.longitude()
            + "&current=temperature_2m,precipitation,rain,weather_code&timezone=auto")
        .retrieve()
        .body(JsonNode.class);
    JsonNode current = forecast == null ? null : forecast.path("current");
    if (current == null || current.isMissingNode()) {
      throw new IllegalStateException("天气接口没有返回当前天气");
    }
    int weatherCode = current.path("weather_code").asInt(-1);
    double temperature = current.path("temperature_2m").asDouble();
    double precipitation = current.path("precipitation").asDouble(current.path("rain").asDouble(0));
    return new WeatherReport(location.name(), describe(weatherCode), temperature, precipitation, "Open-Meteo");
  }

  private String describe(int code) {
    if (code == 0) {
      return "晴";
    }
    if (code <= 3) {
      return "多云";
    }
    if (code == 45 || code == 48) {
      return "雾";
    }
    if (code >= 51 && code <= 67) {
      return "小雨或冻雨";
    }
    if (code >= 71 && code <= 77) {
      return "降雪";
    }
    if (code >= 80 && code <= 82) {
      return "阵雨";
    }
    if (code >= 95) {
      return "雷雨";
    }
    return "未知天气";
  }

  private record CityLocation(String name, double latitude, double longitude) {
  }
}
