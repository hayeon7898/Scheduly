package com.workingdead.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 카카오 API 설정
 */
@Configuration
@ConfigurationProperties(prefix = "kakao")
@Getter
@Setter
public class KakaoConfig {

    private String restApiKey;
    private String adminKey;
    private String channelId;
    private String botId;

    // Bot API Base URL
    public static final String BOT_API_BASE_URL = "https://bot-api.kakao.com";

    @Bean
    public RestTemplate kakaoRestTemplate() {
        return new RestTemplate();
    }

    /**
     * 개발용 botId (botId! 형식)
     */
    public String getDevBotId() {
        return botId + "!";
    }
}
