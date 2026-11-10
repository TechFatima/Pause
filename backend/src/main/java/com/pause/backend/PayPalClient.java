package com.pause.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.Map;

/**
 * PayPalClient handles all communication with the PayPal Sandbox API.
 *
 * Spring concept - @Component: putting this annotation on a class tells Spring
 * "create one instance of this class when the app starts and keep it available".
 * You never call 'new PayPalClient()' yourself — Spring does it for you.
 */
@Component
public class PayPalClient {

    private static final String BASE_URL = "https://api-m.sandbox.paypal.com";

    // Spring concept - @Value: reads a value from application.properties (or
    // application-local.properties) and puts it straight into this field.
    // The string inside is the property key surrounded by ${ }.
    private final String clientId;
    private final String secret;

    private final RestClient restClient;

    /**
     * Spring concept - Constructor injection: instead of Spring setting fields
     * directly, it calls this constructor and passes the values in. This is the
     * preferred style because the class can never be created without its
     * dependencies — they are guaranteed to be present.
     *
     * @Value on a constructor parameter works the same way as on a field:
     * Spring reads the property and passes it as the argument.
     */
    public PayPalClient(
            @Value("${paypal.client-id}") String clientId,
            @Value("${paypal.secret}")    String secret
    ) {
        this.clientId = clientId;
        this.secret   = secret;

        // Build one reusable HTTP client pointed at the sandbox base URL.
        // RestClient is Spring Boot's modern built-in HTTP client — no extra library needed.
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    // -----------------------------------------------------------------------
    // Package-private result types (simple data carriers, no logic)
    // -----------------------------------------------------------------------

    /** Holds the result of a successful createAuthorizeOrder call. */
    record OrderResult(String id, String status) {}

    /** Holds error details when PayPal returns an error response. */
    record PayPalError(int httpStatus, String message) {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Fetches a short-lived OAuth access token from PayPal.
     * The token is returned as a plain String and is NEVER logged.
     *
     * @throws PayPalClientException if credentials are wrong or PayPal is unreachable.
     */
    String getAccessToken() {
        // PayPal expects Basic Auth: Base64("clientId:secret")
        // We build this inline and never store it in a field so it can't leak.
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + secret).getBytes());

        try {
            // POST /v1/oauth2/token with grant_type=client_credentials
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v1/oauth2/token")
                    .header("Authorization", "Basic " + credentials)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("access_token")) {
                throw new PayPalClientException(400, "PayPal did not return an access token.");
            }

            // Return the token value — never log or expose it.
            return (String) response.get("access_token");

        } catch (RestClientResponseException ex) {
            // RestClient throws this when PayPal replies with a 4xx or 5xx status.
            throw new PayPalClientException(
                    ex.getStatusCode().value(),
                    safeErrorMessage(ex)
            );
        }
    }

    /**
     * Creates a PayPal order with intent=AUTHORIZE for the given USD amount.
     * The order is only authorized (reserved), not captured (not charged yet).
     *
     * @param amount  USD amount as a string, e.g. "10.00"
     * @return OrderResult containing the order id and status.
     * @throws PayPalClientException if PayPal returns an error.
     */
    public OrderResult createAuthorizeOrder(String amount) {
        String token = getAccessToken(); // may throw PayPalClientException

        // Build the order request body as a plain string of JSON.
        // We keep it as a string to avoid needing extra JSON libraries for now.
        String requestBody = """
                {
                  "intent": "AUTHORIZE",
                  "purchase_units": [
                    {
                      "amount": {
                        "currency_code": "USD",
                        "value": "%s"
                      }
                    }
                  ]
                }
                """.formatted(amount);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v2/checkout/orders")
                    .header("Authorization", "Bearer " + token) // token used here, never logged
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new PayPalClientException(500, "Empty response from PayPal orders API.");
            }

            return new OrderResult(
                    (String) response.get("id"),
                    (String) response.get("status")
            );

        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(
                    ex.getStatusCode().value(),
                    safeErrorMessage(ex)
            );
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts PayPal's error message from a failed HTTP response.
     * Never includes the Authorization header or our credentials.
     */
    private String safeErrorMessage(RestClientResponseException ex) {
        // getResponseBodyAsString() gives us the raw JSON error body PayPal sent back.
        // It contains things like {"name":"INVALID_CLIENT","message":"..."} — safe to surface.
        String body = ex.getResponseBodyAsString();
        return body.isBlank() ? ex.getMessage() : body;
    }

    // -----------------------------------------------------------------------
    // Exception type
    // -----------------------------------------------------------------------

    /**
     * Thrown when PayPal returns an error. Carries the HTTP status and
     * a safe error message (no secrets, no tokens).
     */
    static class PayPalClientException extends RuntimeException {
        final int httpStatus;

        PayPalClientException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
