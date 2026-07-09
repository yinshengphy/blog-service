package cn.yinsheng.blog.rag.skill.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yinsheng.blog.rag.intent.IntentResult;
import cn.yinsheng.blog.rag.intent.IntentType;
import cn.yinsheng.blog.rag.skill.SkillContext;
import cn.yinsheng.blog.rag.skill.SkillRequest;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class WeatherSkillTest {
  @Test
  void shouldUseWeatherProvider() {
    WeatherProvider provider = mock(WeatherProvider.class);
    when(provider.currentWeather("上海")).thenReturn(new WeatherReport("上海", "多云", 28.5, 0, "mock"));
    WeatherSkill skill = new WeatherSkill(provider);

    String answer = skill.execute(request("上海今天下雨吗？")).answer();

    assertThat(answer).contains("上海", "多云", "来源：mock");
    verify(provider).currentWeather("上海");
  }

  @Test
  void shouldAskCityWhenMissing() {
    WeatherSkill skill = new WeatherSkill(city -> new WeatherReport(city, "晴", 20, 0, "mock"));

    assertThat(skill.execute(request("今天下雨吗？")).answer()).contains("哪个城市");
  }

  @Test
  void shouldHandleProviderFailureGracefully() {
    WeatherSkill skill = new WeatherSkill(city -> {
      throw new IllegalStateException("天气服务不可用");
    });

    assertThat(skill.execute(request("上海今天下雨吗？")).answer())
        .contains("暂时没能查到 上海 的天气数据");
  }

  private SkillRequest request(String question) {
    return new SkillRequest(
        question,
        IntentResult.of(IntentType.WEATHER_QUERY, 1, "test"),
        SkillContext.publicUser(ZoneId.of("Asia/Shanghai"), "trace-test")
    );
  }
}
