package com.pause.backend;

/**
 * Contract for looking up how old a domain is (in days).
 * The real implementation would call a WHOIS API; the mock uses a fixed map.
 * Keeping this as an interface means we can swap implementations without
 * changing any of the tools that depend on it.
 */
public interface DomainAgeService {

    /**
     * Returns the age of the domain in days, or a large number if unknown.
     * Implementations must NEVER make network requests to the domain itself —
     * only to a WHOIS-style lookup service (or return a fixed value in the mock).
     *
     * @param domain the bare domain name, e.g. "paypa1-secure.xyz"
     * @return age in days (0 = brand new, large number = established)
     */
    int getDomainAgeDays(String domain);
}
