package com.pause.backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * PayeeRepository gives us standard database operations for Payee,
 * plus one custom finder generated automatically by Spring.
 */
public interface PayeeRepository extends JpaRepository<Payee, Long> {

    /**
     * Spring/JPA concept - derived query methods: Spring reads the method name
     * and generates the SQL automatically. "findByUserId" becomes
     * "SELECT * FROM payee WHERE user_id = ?". No SQL or implementation needed.
     */
    List<Payee> findByUserId(Long userId);
    long countByUserId(Long userId);
}
