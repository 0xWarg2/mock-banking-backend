package com.mockbank.transaction.client;

import com.mockbank.transaction.client.dto.AccountResponse;
import com.mockbank.transaction.client.dto.MoneyRequest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
public class AccountServiceClient {

    private static final String SITE_ID_HEADER = "X-Site-Id";

    private final RestClient restClient;

    public AccountServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.account-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    String siteId = MDC.get("siteId");
                    if (siteId != null) {
                        request.getHeaders().set(SITE_ID_HEADER, siteId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    public AccountResponse getAccount(Long accountId) {
        return restClient.get()
                .uri("/internal/accounts/{id}", accountId)
                .retrieve()
                .body(AccountResponse.class);
    }

    public AccountResponse debit(Long accountId, BigDecimal amount) {
        return restClient.post()
                .uri("/internal/accounts/{id}/debit", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MoneyRequest(amount))
                .retrieve()
                .body(AccountResponse.class);
    }

    public AccountResponse credit(Long accountId, BigDecimal amount) {
        return restClient.post()
                .uri("/internal/accounts/{id}/credit", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MoneyRequest(amount))
                .retrieve()
                .body(AccountResponse.class);
    }
}
