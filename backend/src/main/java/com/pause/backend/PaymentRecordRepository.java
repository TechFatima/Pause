package com.pause.backend;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    /**
     * Finds a record by its PayPal order id.
     * Spring derives the SQL from the method name automatically.
     */
    Optional<PaymentRecord> findByPaypalOrderId(String paypalOrderId);

    /**
     * Finds a record by PayPal order id and acquires a PESSIMISTIC_WRITE lock.
     *
     * JPA concept - @Lock(PESSIMISTIC_WRITE): tells the database to lock the
     * row until the current transaction commits. Any other transaction trying
     * to lock the same row will wait, so two simultaneous return requests
     * cannot both read DECIDED_* and both proceed to authorize/capture.
     *
     * @Query is required here because Spring cannot generate a locking query
     * from the method name alone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PaymentRecord r WHERE r.paypalOrderId = :orderId")
    Optional<PaymentRecord> findByPaypalOrderIdForUpdate(@Param("orderId") String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PaymentRecord r WHERE r.trustedToken = :token")
    Optional<PaymentRecord> findByTrustedTokenForUpdate(@Param("token") String token);
}
