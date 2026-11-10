package com.pause.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.List;
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

    private final String clientId;
    private final String secret;
    private final RestClient restClient;

    /**
     * Spring concept - Constructor injection: Spring calls this constructor and
     * passes in the values it finds in application-local.properties.
     *
     * Spring concept - @Value: reads a named property and injects its value.
     */
    public PayPalClient(
            @Value("${paypal.client-id}") String clientId,
            @Value("${paypal.secret}")    String secret
    ) {
        this.clientId = clientId;
        this.secret   = secret;

        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    // -----------------------------------------------------------------------
    // Result types (plain data carriers, no logic)
    // -----------------------------------------------------------------------

    /** Result of createAuthorizeOrder: order id, status, and the URL where the buyer approves. */
    record OrderResult(String id, String status, String approveUrl) {}

    /** Result of authorizeOrder: the authorization id and its status. */
    record AuthorizationResult(String authorizationId, String status) {}

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
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + secret).getBytes());

        try {
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

            return (String) response.get("access_token"); // never log this value
        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(ex.getStatusCode().value(), safeErrorMessage(ex));
        }
    }

    /**
     * Creates a PayPal order with intent=AUTHORIZE for the given USD amount.
     * Includes return_url and cancel_url so PayPal knows where to redirect the buyer.
     *
     * @param amount USD amount as a string, e.g. "10.00"
     * @return OrderResult with id, status, and the URL the buyer must open to approve.
     * @throws PayPalClientException if PayPal returns an error.
     */
    public OrderResult createAuthorizeOrder(String amount) {
        String token = getAccessToken();

        String requestBody = """
                {
                  "intent": "AUTHORIZE",
                  "payment_source": {
                    "paypal": {
                      "experience_context": {
                        "return_url": "http://localhost:8080/paypal/return",
                        "cancel_url": "http://localhost:8080/paypal/cancel"
                      }
                    }
                  },
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

            // Walk the "links" array to find the buyer-approval URL.
            // PayPal uses rel="payer-action" in sandbox; some flows use rel="approve".
            String approveUrl = extractLink(response, "payer-action", "approve");

            return new OrderResult(
                    (String) response.get("id"),
                    (String) response.get("status"),
                    approveUrl
            );
        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(ex.getStatusCode().value(), safeErrorMessage(ex));
        }
    }

    /**
     * Authorizes (places a hold on) a buyer-approved order.
     * The buyer must have visited the approveUrl first.
     *
     * @param orderId the order id returned by createAuthorizeOrder
     * @return AuthorizationResult with the authorization id and status (CREATED = held).
     * @throws PayPalClientException if PayPal returns an error.
     */
    public AuthorizationResult authorizeOrder(String orderId) {
        String token = getAccessToken();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v2/checkout/orders/{id}/authorize", orderId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")  // PayPal requires a JSON body even if empty
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new PayPalClientException(500, "Empty response from PayPal authorize API.");
            }

            // The authorization id lives at: purchase_units[0].payments.authorizations[0].id
            String authId   = extractAuthorizationId(response);
            String authStatus = extractAuthorizationStatus(response);

            return new AuthorizationResult(authId, authStatus);
        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(ex.getStatusCode().value(), safeErrorMessage(ex));
        }
    }

    /**
     * Captures (charges) a previously created authorization.
     *
     * @param authId the authorization id returned by authorizeOrder
     * @return the resulting status string, e.g. "COMPLETED"
     * @throws PayPalClientException if PayPal returns an error.
     */
    public String captureAuthorization(String authId) {
        String token = getAccessToken();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v2/payments/authorizations/{id}/capture", authId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}") // empty body required by PayPal
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return "COMPLETED"; // PayPal occasionally returns 204 on capture
            }
            return (String) response.getOrDefault("status", "COMPLETED");
        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(ex.getStatusCode().value(), safeErrorMessage(ex));
        }
    }

    /**
     * Voids (releases) a previously created authorization.
     * PayPal returns HTTP 204 No Content on success, so we never try to parse a body.
     *
     * @param authId the authorization id returned by authorizeOrder
     * @return "VOIDED" always on success
     * @throws PayPalClientException if PayPal returns an error.
     */
    public String voidAuthorization(String authId) {
        String token = getAccessToken();

        try {
            // toBodilessEntity() tells RestClient "don't try to read a response body".
            // This prevents a crash when PayPal returns 204 No Content.
            restClient.post()
                    .uri("/v2/payments/authorizations/{id}/void", authId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();

            return "VOIDED";
        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(ex.getStatusCode().value(), safeErrorMessage(ex));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Walks the "links" array in a PayPal response and returns the href of the
     * first link whose "rel" matches any of the given candidate values.
     * Returns null if none is found.
     */
    @SuppressWarnings("unchecked")
    private String extractLink(Map<String, Object> response, String... relCandidates) {
        Object linksObj = response.get("links");
        if (!(linksObj instanceof List<?> links)) return null;

        for (Object item : links) {
            if (!(item instanceof Map<?, ?> link)) continue;
            String rel  = (String) link.get("rel");
            String href = (String) link.get("href");
            for (String candidate : relCandidates) {
                if (candidate.equals(rel)) return href;
            }
        }
        return null;
    }

    /** Extracts the authorization id from the deeply nested PayPal authorize response. */
    @SuppressWarnings("unchecked")
    private String extractAuthorizationId(Map<String, Object> response) {
        try {
            List<Map<String, Object>> units = (List<Map<String, Object>>) response.get("purchase_units");
            Map<String, Object> payments    = (Map<String, Object>) units.get(0).get("payments");
            List<Map<String, Object>> auths = (List<Map<String, Object>>) payments.get("authorizations");
            return (String) auths.get(0).get("id");
        } catch (Exception e) {
            throw new PayPalClientException(500, "Could not parse authorization id from PayPal response.");
        }
    }

    /** Extracts the authorization status from the PayPal authorize response. */
    @SuppressWarnings("unchecked")
    private String extractAuthorizationStatus(Map<String, Object> response) {
        try {
            List<Map<String, Object>> units = (List<Map<String, Object>>) response.get("purchase_units");
            Map<String, Object> payments    = (Map<String, Object>) units.get(0).get("payments");
            List<Map<String, Object>> auths = (List<Map<String, Object>>) payments.get("authorizations");
            return (String) auths.get(0).get("status");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Extracts PayPal's error message body from a failed HTTP response.
     * Never includes credentials, tokens, or Authorization headers.
     */
    private String safeErrorMessage(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        return body.isBlank() ? ex.getMessage() : body;
    }

    // -----------------------------------------------------------------------
    // Exception type
    // -----------------------------------------------------------------------

    /**
     * Thrown when PayPal returns an error. Carries the HTTP status and a safe
     * error message (no secrets, no tokens).
     */
    static class PayPalClientException extends RuntimeException {
        final int httpStatus;

        PayPalClientException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
