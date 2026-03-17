package com.mockbank.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class SiteContextFilter implements GlobalFilter, Ordered {

    private static final String SITE_ID_HEADER = "X-Site-Id";

    @Value("${app.site-id:LOCAL}")
    private String defaultSiteId;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String siteId = exchange.getRequest().getHeaders().getFirst(SITE_ID_HEADER);
        if (siteId == null || siteId.isBlank()) {
            siteId = defaultSiteId;
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(SITE_ID_HEADER, siteId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -2; // Run before AuthFilter (-1)
    }
}
