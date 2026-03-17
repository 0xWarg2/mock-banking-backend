package com.mockbank.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (apiKey != null) {
                return Mono.just(apiKey);
            }
            var remoteAddress = exchange.getRequest().getRemoteAddress();
            String key = remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
            return Mono.just(key);
        };
    }
}
