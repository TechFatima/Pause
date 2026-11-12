package com.pause.backend;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * DemoDataController exposes read-only, account-scoped dashboard data.
 *
 * Spring concept - @RequestMapping on the class: sets a common URL prefix
 * for all endpoints in this controller. Both methods below automatically
 * start with "/api", so we only write the rest of the path on each method.
 */
@RestController
@RequestMapping("/api")
public class DemoDataController {

    private final PaymentRecordRepository paymentRepo;
    private final PayeeRepository payeeRepository;
    private final UserIdentityService userIdentityService;

    // Constructor injection: Spring provides the repositories it already created.
    public DemoDataController(
            PaymentRecordRepository paymentRepo,
            PayeeRepository payeeRepository,
            UserIdentityService userIdentityService
    ) {
        this.paymentRepo = paymentRepo;
        this.payeeRepository = payeeRepository;
        this.userIdentityService = userIdentityService;
    }

    /**
     * Returns the signed-in account's safe public profile.
     */
    @GetMapping({"/users/me", "/users/demo"})
    public ResponseEntity<UserSummary> getDemoUser(Authentication authentication) {
        AppUser user = userIdentityService.requireUser(authentication);
        return ResponseEntity.ok(UserSummary.from(user));
    }

    /**
     * Returns payment activity for the signed-in account, newest first.
     */
    @GetMapping("/payments")
    public ResponseEntity<List<PaymentActivity>> getPayments(Authentication authentication) {
        Long userId = userIdentityService.requireUser(authentication).getId();
        List<PaymentRecord> records = paymentRepo.findByUserId(
                userId,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return ResponseEntity.ok(records.stream().map(PaymentActivity::from).toList());
    }

    /**
     * Returns the number of known payees for the signed-in account.
     */
    @GetMapping("/payees/count")
    public ResponseEntity<Map<String, Object>> getPayeeCount(Authentication authentication) {
        Long userId = userIdentityService.requireUser(authentication).getId();
        long count = payeeRepository.countByUserId(userId);
        return ResponseEntity.ok(Map.of(
                "knownPayeeCount", count
        ));
    }
}
