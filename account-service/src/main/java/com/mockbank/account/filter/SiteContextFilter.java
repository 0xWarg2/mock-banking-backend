package com.mockbank.account.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SiteContextFilter extends OncePerRequestFilter {

    private static final String SITE_ID_HEADER = "X-Site-Id";
    private static final String MDC_KEY = "siteId";

    @Value("${app.site-id:LOCAL}")
    private String defaultSiteId;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String siteId = request.getHeader(SITE_ID_HEADER);
        if (siteId == null || siteId.isBlank()) {
            siteId = defaultSiteId;
        }
        MDC.put(MDC_KEY, siteId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
