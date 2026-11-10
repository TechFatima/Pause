package com.pause.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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
    // Base URL for PayPal's return/cancel redirects; read from application.properties.
    private final String callbackBaseUrl;

    @Autowired
    public PayPalClient(
            @Value("${paypal.client-id}")           String clientId,
            @Value("${paypal.secret}")              String secret,
            @Value("${paypal.callback-base-url}")   String callbackBaseUrl
    ) {
        this(clientId, secret, callbackBaseUrl, RestClient.builder());
    }

    // Test constructor: lets tests connect RestClient to a local mock server.
    PayPalClient(
            String clientId,
            String secret,
            String callbackBaseUrl,
            RestClient.Builder restClientBuilder
    ) {
        this.clientId        = clientId;
        this.secret          = secret;
        this.callbackBaseUrl = callbackBaseUrl;

        this.restClient = restClientBuilder
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

    /**
     * Result of getOrderDetails: the PayPal order status and any existing authorization.
     * existingAuthorizationId / existingAuthorizationStatus are null if no authorization exists.
     */
    record OrderDetails(
            String orderStatus,
            String existingAuthorizationId,
            String existingAuthorizationStatus,
            String existingCaptureStatus,
            boolean authorizationStateKnown
    ) {
        OrderDetails(String orderStatus, String authorizationId, String authorizationStatus) {
            this(orderStatus, authorizationId, authorizationStatus, null, true);
        }

        OrderDetails(
                String orderStatus,
                String authorizationId,
                String authorizationStatus,
                String captureStatus
        ) {
            this(orderStatus, authorizationId, authorizationStatus, captureStatus, true);
        }
    }

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
        } catch (RestClientException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        } catch (PayPalClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
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
                        "return_url": "%s/paypal/return",
                        "cancel_url": "%s/paypal/cancel"
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
                """.formatted(callbackBaseUrl, callbackBaseUrl, amount);

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
        } catch (RestClientException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        } catch (PayPalClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
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
        } catch (RestClientException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        } catch (PayPalClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        }
    }

    /**
     * Captures (charges) a previously created authorization.
     * Only returns the status string when PayPal explicitly confirms a value.
     * Throws PayPalClientException if the response is missing or status is absent,
     * so callers never assume COMPLETED without explicit confirmation.
     *
     * @param authId the authorization id returned by authorizeOrder
     * @return the status string returned by PayPal, e.g. "COMPLETED"
     * @throws PayPalClientException if PayPal returns an error or status is unconfirmed.
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

            // Never assume COMPLETED — require PayPal to confirm the status explicitly.
            if (response == null) {
                throw new PayPalClientException(500,
                        "Capture API returned no response body — status unconfirmed.");
            }
            String capturedStatus = (String) response.get("status");
            if (capturedStatus == null) {
                throw new PayPalClientException(500,
                        "Capture API response missing status field — status unconfirmed.");
            }
            return capturedStatus;
        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(ex.getStatusCode().value(), safeErrorMessage(ex));
        } catch (RestClientException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        } catch (PayPalClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        }
    }

    /**
     * Retrieves the current state of a PayPal order. READ-ONLY — no side effects.
     * Used to safely inspect PayPal state before retrying failed operations.
     *
     * @param orderId the PayPal order id
     * @return OrderDetails with the order status and any existing authorization (nullable fields).
     * @throws PayPalClientException if PayPal returns an error or the order is not found.
     */
    public OrderDetails getOrderDetails(String orderId) {
        String token = getAccessToken();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/v2/checkout/orders/{id}", orderId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new PayPalClientException(500, "Empty response from PayPal get-order API.");
            }

            String orderStatus = (String) response.get("status");

            // Extract any existing authorization from purchase_units[0].payments.authorizations[0]
            String existingAuthId     = null;
            String existingAuthStatus = null;
            String existingCaptureStatus = null;
            boolean authorizationStateKnown = true;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> units =
                        (List<Map<String, Object>>) response.get("purchase_units");
                if (units == null || units.isEmpty()) {
                    authorizationStateKnown = false;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payments =
                            (Map<String, Object>) units.get(0).get("payments");
                    if (payments != null) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> auths =
                                (List<Map<String, Object>>) payments.get("authorizations");
                        if (auths != null && !auths.isEmpty()) {
                            existingAuthId     = (String) auths.get(0).get("id");
                            existingAuthStatus = (String) auths.get(0).get("status");
                            if (existingAuthId == null || existingAuthStatus == null) {
                                authorizationStateKnown = false;
                            }
                        }
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> captures =
                                (List<Map<String, Object>>) payments.get("captures");
                        if (captures != null && captures.size() == 1) {
                            existingCaptureStatus = (String) captures.get(0).get("status");
                            if (existingCaptureStatus == null) authorizationStateKnown = false;
                        } else if (captures != null && captures.size() > 1) {
                            authorizationStateKnown = false;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Parsing failed — report that the payment state is unknown.
                authorizationStateKnown = false;
            }

            return new OrderDetails(
                    orderStatus, existingAuthId, existingAuthStatus, existingCaptureStatus,
                    authorizationStateKnown);
        } catch (RestClientResponseException ex) {
            throw new PayPalClientException(ex.getStatusCode().value(), safeErrorMessage(ex));
        } catch (RestClientException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        } catch (PayPalClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
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
        } catch (RestClientException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
        } catch (PayPalClientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PayPalClientException(502, safeErrorMessage(null));
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
        // PayPal error bodies can contain sensitive details; never return them to callers.
        return "PayPal could not complete the request. Please try again.";
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
