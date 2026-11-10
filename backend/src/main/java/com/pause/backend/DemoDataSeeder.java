package com.pause.backend;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * DemoDataSeeder populates the database with one demo user "Rose" and a
 * realistic payment history the first time the app starts.
 *
 * Spring concept - CommandLineRunner: an interface with one method, run().
 * Spring calls run() once, right after the app finishes starting up.
 * It's the standard way to seed demo data.
 *
 * Spring concept - @Component: tells Spring to create one instance of this
 * class and manage it. Combined with CommandLineRunner, Spring will
 * automatically call run() at startup.
 *
 * Spring concept - @Transactional: wraps the entire run() method in one
 * database transaction. If anything fails, no data is saved (all-or-nothing).
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    // Spring injects these via constructor injection.
    private final AppUserRepository userRepo;
    private final PayeeRepository payeeRepo;
    private final PaymentRecordRepository paymentRepo;

    public DemoDataSeeder(
            AppUserRepository userRepo,
            PayeeRepository payeeRepo,
            PaymentRecordRepository paymentRepo
    ) {
        this.userRepo    = userRepo;
        this.payeeRepo   = payeeRepo;
        this.paymentRepo = paymentRepo;
    }

    @Override
    @Transactional
    public void run(String... args) {

        // -----------------------------------------------------------------------
        // 1. Create demo user "Rose"
        // -----------------------------------------------------------------------
        AppUser rose = new AppUser();
        rose.setName("Rose");
        rose.setMaxPerPayment(new BigDecimal("200.00"));
        rose.setWeeklyCap(new BigDecimal("500.00"));
        rose.setNewPayeesNeedApproval(true);
        rose.setTrustedContactName("Sam");
        rose = userRepo.save(rose);  // save() returns the entity with the generated id

        Long userId = rose.getId();

        // -----------------------------------------------------------------------
        // 2. Seed 3 known payees for Rose
        // -----------------------------------------------------------------------
        String[] payeeNames = {"Grocery Store", "Pharmacy", "Electric Company"};

        for (String name : payeeNames) {
            Payee p = new Payee();
            p.setUserId(userId);
            p.setName(name);
            payeeRepo.save(p);
        }

        // -----------------------------------------------------------------------
        // 3. Seed ~20 past payments spread across the last 60 days
        //
        // new Random(42) — using a fixed seed means the same data is generated
        // every time the app starts: same amounts, same days, same hours.
        // The dates are relative to "now", so the timestamps are always recent.
        // -----------------------------------------------------------------------
        Random rng = new Random(42);
        LocalDateTime now = LocalDateTime.now();

        // Amounts in cents (2000–5000) to avoid floating-point surprises,
        // then divided by 100 to get dollars.
        int[] amountsCents = {
            2150, 3475, 4800, 2200, 3900, 2750, 4100, 2050, 3300, 4550,
            2600, 3150, 4700, 2350, 3650, 2900, 4250, 2450, 3050, 4400
        };

        // Spread payments across the last 60 days. Use a fixed sequence of
        // day offsets derived from the seeded RNG for determinism.
        for (int i = 0; i < amountsCents.length; i++) {

            // Day offset: random integer in [1, 60], then count back from now.
            int daysBack = 1 + rng.nextInt(60);

            // Hour: mostly daytime (8–20), occasionally outside that range.
            int hour = 8 + rng.nextInt(13);   // 8..20

            LocalDateTime createdAt = now
                    .minusDays(daysBack)
                    .withHour(hour)
                    .withMinute(rng.nextInt(60))
                    .withSecond(0)
                    .withNano(0);

            // Round-robin through the three known payees.
            String payeeName = payeeNames[i % payeeNames.length];

            // Convert cents to BigDecimal dollars.
            BigDecimal amount = new BigDecimal(amountsCents[i])
                    .movePointLeft(2);   // e.g. 2150 -> 21.50

            PaymentRecord record = new PaymentRecord();
            record.setUserId(userId);
            record.setPayeeName(payeeName);
            record.setAmount(amount);
            record.setCreatedAt(createdAt);
            record.setStatus("COMPLETED");
            // paypalOrderId and authorizationId are null for historical records.

            paymentRepo.save(record);
        }

        System.out.println("[DemoDataSeeder] Seeded user Rose (id=" + userId
                + ") with " + amountsCents.length + " payment records.");
    }
}
