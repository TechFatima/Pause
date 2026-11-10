package com.pause.backend;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * DemoDataController exposes two read-only endpoints to inspect the
 * seeded demo data.
 *
 * Spring concept - @RequestMapping on the class: sets a common URL prefix
 * for all endpoints in this controller. Both methods below automatically
 * start with "/api", so we only write the rest of the path on each method.
 */
@RestController
@RequestMapping("/api")
public class DemoDataController {

    private final AppUserRepository userRepo;
    private final PaymentRecordRepository paymentRepo;

    // Constructor injection: Spring provides the repositories it already created.
    public DemoDataController(
            AppUserRepository userRepo,
            PaymentRecordRepository paymentRepo
    ) {
        this.userRepo    = userRepo;
        this.paymentRepo = paymentRepo;
    }

    /**
     * GET /api/users/demo
     * Returns the demo user "Rose" as JSON.
     * We always use the first user in the database (id=1) for the demo.
     */
    @GetMapping("/users/demo")
    public ResponseEntity<?> getDemoUser() {
        // findById returns an Optional — present if the row exists, empty if not.
        // map() converts the AppUser to a 200 response; orElse gives a 404.
        return userRepo.findById(1L)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/payments
     * Returns all payment records for the demo user (id=1), newest first.
     */
    @GetMapping("/payments")
    public ResponseEntity<List<PaymentRecord>> getPayments() {
        // Sort.by(DESC, "createdAt") tells Spring Data to add ORDER BY created_at DESC.
        List<PaymentRecord> records = paymentRepo.findByUserId(
                1L,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return ResponseEntity.ok(records);
    }

    /**
     * GET /api/payees  (bonus: useful for debugging the seeded payees)
     * Returns a summary of how many known payees the demo user has.
     */
    @GetMapping("/payees/count")
    public ResponseEntity<Map<String, Object>> getPayeeCount() {
        long count = userRepo.count(); // just to confirm the user exists
        return ResponseEntity.ok(Map.of(
                "note", "Use /api/payments to see payment history",
                "userCount", count
        ));
    }
}
