package com.pause.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "llm.api-key=")
class AuthenticationAndNotificationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PaymentRecordRepository paymentRepository;
    @Autowired private UserNotificationRepository notificationRepository;
    @Autowired private NotificationService notificationService;

    @Test
    void privateApiRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationHashesPasswordAndLoginCreatesSession() throws Exception {
        String email = uniqueEmail();
        String password = "Rose-strong-demo-2026";
        String request = "{\"name\":\"Rosa\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}";

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated());

        AppUser saved = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertNotEquals(password, saved.getPasswordHash());
        assertTrue(passwordEncoder.matches(password, saved.getPasswordHash()));

        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertFalse(login.getResponse().getContentAsString().contains("passwordHash"));
        mockMvc.perform(get("/api/auth/me").session((org.springframework.mock.web.MockHttpSession)
                        login.getRequest().getSession(false)))
                .andExpect(status().isOk());
    }

    @Test
    void usersCannotReadAnotherUsersPaymentsOrNotifications() throws Exception {
        AppUser owner = createUser(uniqueEmail(), "Owner");
        AppUser other = createUser(uniqueEmail(), "Other");

        PaymentRecord otherPayment = new PaymentRecord();
        otherPayment.setUserId(other.getId());
        otherPayment.setPayeeName("Private payee");
        otherPayment.setAmount(new BigDecimal("45.00"));
        otherPayment.setDecision(Decision.HOLD);
        otherPayment.setStatus("HELD");
        otherPayment.setCreatedAt(java.time.LocalDateTime.now());
        paymentRepository.save(otherPayment);

        notificationService.createOnce(other.getId(), otherPayment.getId(), "PAYMENT_HELD",
                "Private notification", "This belongs to another account.");
        UserNotification privateNotification = notificationRepository.findAll().stream()
                .filter(n -> other.getId().equals(n.getUserId()))
                .findFirst().orElseThrow();

        mockMvc.perform(get("/api/payments").with(user(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString()
                        .contains("Private payee")));
        mockMvc.perform(get("/api/notifications").with(user(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString()
                        .contains("Private notification")));
        mockMvc.perform(post("/api/notifications/" + privateNotification.getId() + "/read")
                        .with(user(owner.getEmail())).with(csrf()))
                .andExpect(status().isNotFound());

        assertNull(notificationRepository.findById(privateNotification.getId()).orElseThrow().getReadAt());
    }

    @Test
    void notificationsAreDeduplicatedAndCanBeMarkedRead() throws Exception {
        AppUser owner = createUser(uniqueEmail(), "Notice owner");
        PaymentRecord payment = new PaymentRecord();
        payment.setUserId(owner.getId());
        payment.setPayeeName("Known Store");
        payment.setAmount(new BigDecimal("30.00"));
        payment.setStatus("DECIDED_ALLOW");
        payment.setCreatedAt(java.time.LocalDateTime.now());
        PaymentRecord savedPayment = paymentRepository.save(payment);

        notificationService.createOnce(owner.getId(), savedPayment.getId(), "PAYMENT_ASSESSED",
                "Payment checked", "Rules finished the safety check.");
        notificationService.createOnce(owner.getId(), savedPayment.getId(), "PAYMENT_ASSESSED",
                "Payment checked", "Duplicate delivery must not create a second item.");

        long count = notificationRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()).stream()
                .filter(n -> n.getPaymentRecordId().equals(savedPayment.getId()))
                .count();
        assertEquals(1, count);

        Long notificationId = notificationRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()).stream()
                .filter(n -> n.getPaymentRecordId().equals(savedPayment.getId()))
                .findFirst().orElseThrow().getId();
        mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                        .with(user(owner.getEmail())).with(csrf()))
                .andExpect(status().isOk());

        assertNotNull(notificationRepository.findById(notificationId).orElseThrow().getReadAt());
    }

    private AppUser createUser(String email, String name) {
        AppUser user = new AppUser();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("test-password-with-12-chars"));
        user.setMaxPerPayment(new BigDecimal("200.00"));
        user.setWeeklyCap(new BigDecimal("500.00"));
        user.setNewPayeesNeedApproval(true);
        return userRepository.save(user);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
