package com.pause.backend;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mock implementation of DomainAgeService that returns fixed ages from a map.
 * Makes NO network requests — safe for demos and tests.
 * Replace this class with a real WHOIS client when ready.
 *
 * Spring concept - @Component on an interface implementation: Spring sees this
 * class, creates one instance, and whenever something asks for a DomainAgeService
 * it injects this mock automatically.
 */
@Component
public class MockDomainAgeService implements DomainAgeService {

    // Fixed domain-age map (domain -> age in days).
    // Suspicious domains are given a very young age to trigger the VERY_NEW_DOMAIN check.
    private static final Map<String, Integer> AGES = Map.of(
            "paypa1-secure.xyz", 2,       // lookalike scam domain — brand new
            "paypal-secure.com", 3,       // another lookalike — brand new
            "paypal.com",        9000,    // real PayPal — well established
            "amazon.com",        9000,
            "google.com",        9000
    );

    // Any domain not in the map is assumed to be established (3000 days = ~8 years).
    private static final int DEFAULT_AGE = 3000;

    @Override
    public int getDomainAgeDays(String domain) {
        if (domain == null) return DEFAULT_AGE;
        // Look up the lower-cased domain; fall back to the default if not found.
        return AGES.getOrDefault(domain.toLowerCase(), DEFAULT_AGE);
    }
}
