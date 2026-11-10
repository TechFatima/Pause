package com.pause.backend;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Tests PayPalClient capture response handling without contacting PayPal. */
class PayPalClientTest {

    private static final String BASE_URL = "https://api-m.sandbox.paypal.com";

    @Test
    void emptyCaptureBodyThrows() {
        TestClient testClient = createClient("AUTH-EMPTY", "");

        PayPalClient.PayPalClientException exception = assertThrows(
                PayPalClient.PayPalClientException.class,
                () -> testClient.client().captureAuthorization("AUTH-EMPTY")
        );

        assertEquals(500, exception.httpStatus);
        testClient.server().verify();
    }

    @Test
    void captureBodyWithoutStatusThrows() {
        TestClient testClient = createClient("AUTH-NO-STATUS", "{}");

        PayPalClient.PayPalClientException exception = assertThrows(
                PayPalClient.PayPalClientException.class,
                () -> testClient.client().captureAuthorization("AUTH-NO-STATUS")
        );

        assertEquals(500, exception.httpStatus);
        testClient.server().verify();
    }

    @Test
    void completedCaptureStatusIsReturned() {
        TestClient testClient = createClient("AUTH-COMPLETED", "{\"status\":\"COMPLETED\"}");

        String result = testClient.client().captureAuthorization("AUTH-COMPLETED");

        assertEquals("COMPLETED", result);
        testClient.server().verify();
    }

    @Test
    void rawPayPalErrorBodyIsNotReturned() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"mock-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v2/payments/authorizations/AUTH-SECRET/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"debug_id\":\"SENSITIVE-DETAIL\",\"secret\":\"NEVER-RETURN\"}"));

        PayPalClient client = new PayPalClient(
                "test-client", "test-secret", "http://localhost", builder);
        PayPalClient.PayPalClientException exception = assertThrows(
                PayPalClient.PayPalClientException.class,
                () -> client.captureAuthorization("AUTH-SECRET"));

        assertEquals("PayPal could not complete the request. Please try again.", exception.getMessage());
        server.verify();
    }

    @Test
    void orderDetailsDistinguishesAuthorizationFromCapture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectAccessToken(server);
        server.expect(requestTo(BASE_URL + "/v2/checkout/orders/ORDER-DETAILS"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":\"COMPLETED\",\"purchase_units\":[{" +
                                "\"payments\":{\"authorizations\":[{" +
                                "\"id\":\"AUTH-DETAILS\",\"status\":\"CREATED\"}]}}]}",
                        MediaType.APPLICATION_JSON));

        PayPalClient client = new PayPalClient(
                "test-client", "test-secret", "http://localhost", builder);
        PayPalClient.OrderDetails details = client.getOrderDetails("ORDER-DETAILS");

        assertEquals("COMPLETED", details.orderStatus());
        assertEquals("CREATED", details.existingAuthorizationStatus());
        assertEquals("AUTH-DETAILS", details.existingAuthorizationId());
        assertNull(details.existingCaptureStatus(),
                "A completed order with a CREATED authorization is not proof of capture");
        server.verify();
    }

    @Test
    void malformedOrderPaymentDetailsAreMarkedUnknown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectAccessToken(server);
        server.expect(requestTo(BASE_URL + "/v2/checkout/orders/ORDER-MALFORMED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":\"APPROVED\",\"purchase_units\":\"unexpected\"}",
                        MediaType.APPLICATION_JSON));

        PayPalClient client = new PayPalClient(
                "test-client", "test-secret", "http://localhost", builder);
        PayPalClient.OrderDetails details = client.getOrderDetails("ORDER-MALFORMED");

        assertFalse(details.authorizationStateKnown());
        server.verify();
    }

    private void expectAccessToken(MockRestServiceServer server) {
        server.expect(requestTo(BASE_URL + "/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"mock-access-token\"}", MediaType.APPLICATION_JSON));
    }

    private TestClient createClient(String authId, String captureResponse) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(BASE_URL + "/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"mock-access-token\"}",
                        MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE_URL + "/v2/payments/authorizations/" + authId + "/capture"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(captureResponse, MediaType.APPLICATION_JSON));

        PayPalClient client = new PayPalClient(
                "test-client", "test-secret", "http://localhost", builder);
        return new TestClient(client, server);
    }

    private record TestClient(PayPalClient client, MockRestServiceServer server) {}
}
