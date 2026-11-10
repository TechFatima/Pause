package com.pause.backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Sort;
import java.util.List;

/**
 * PaymentRecordRepository gives us standard database operations for PaymentRecord,
 * plus a finder that returns all records for a user in a chosen sort order.
 */
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {

    /**
     * Returns all payment records for a given user, sorted by the Sort argument.
     * Spring generates the SQL from the method name: "findByUserId".
     * We pass Sort.by(Sort.Direction.DESC, "createdAt") to get newest-first.
     */
    List<PaymentRecord> findByUserId(Long userId, Sort sort);
}
