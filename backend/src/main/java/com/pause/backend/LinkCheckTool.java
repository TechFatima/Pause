package com.pause.backend;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * LinkCheckTool extracts URLs from the message and checks each one for
 * signs of a scam: lookalike domains, shorteners, raw IPs, non-HTTPS,
 * suspicious TLDs, and very new domain ages.
 *
 * IMPORTANT: this tool NEVER makes any network request to a URL or domain.
 * It only analyses the text of the URL itself, plus the DomainAgeService
 * (which is a mock returning fixed values — no network calls).
 */
@Component
public class LinkCheckTool implements InvestigationTool {

    // -----------------------------------------------------------------------
    // Brand protection list: if the normalized domain CONTAINS one of these
    // brand names but the real host is NOT an official domain for that brand,
    // it is a lookalike.
    // -----------------------------------------------------------------------
    private static final Set<String> PROTECTED_BRANDS = Set.of(
            "paypal", "amazon", "apple", "microsoft", "google", "netflix"
    );

    // Official domains for each brand (used to whitelist the real domain).
    // A domain passes if it IS one of these (or ends with ".<brand>.com/net/org").
    private static final Set<String> OFFICIAL_DOMAINS = Set.of(
            "paypal.com", "amazon.com", "apple.com", "icloud.com",
            "microsoft.com", "live.com", "outlook.com",
            "google.com", "gmail.com", "netflix.com"
    );

    // Known URL-shortener hostnames.
    private static final Set<String> SHORTENERS = Set.of(
            "bit.ly", "tinyurl.com", "t.co", "ow.ly", "goo.gl",
            "is.gd", "buff.ly", "short.link"
    );

    // Suspicious top-level domains.
    private static final Set<String> BAD_TLDS = Set.of(
            ".xyz", ".top", ".click", ".loan", ".work",
            ".gq", ".tk", ".ml", ".cf", ".ga", ".zip"
    );

    // Threshold: domains younger than this (in days) are flagged.
    private static final int NEW_DOMAIN_THRESHOLD_DAYS = 30;

    private final DomainAgeService domainAgeService;

    public LinkCheckTool(DomainAgeService domainAgeService) {
        this.domainAgeService = domainAgeService;
    }

    @Override
    public String name() { return "LinkCheckTool"; }

    @Override
    public ToolFinding run(InvestigationContext ctx) {
        List<Finding> findings = new ArrayList<>();
        List<String> urls = extractUrls(ctx.getMessage());

        for (String url : urls) {
            String host = extractHost(url);
            if (host == null) continue;

            checkNonHttps(url, findings);
            checkRawIp(host, findings);
            checkShortener(host, findings);
            checkSuspiciousTld(host, findings);
            checkLookalikeDomain(host, findings);
            checkDomainAge(host, findings);
        }

        String summary = findings.isEmpty()
                ? "No suspicious links found."
                : findings.size() + " suspicious link issue(s) found.";

        return new ToolFinding(name(), findings, summary);
    }

    // -----------------------------------------------------------------------
    // URL extraction
    // -----------------------------------------------------------------------

    /**
     * Splits the message on whitespace and keeps any token that looks like a URL.
     * Strips trailing punctuation (. , ) ; ! ?) so we get clean hostnames.
     * Regex: \s+ means "one or more whitespace characters" — used only to split.
     */
    private List<String> extractUrls(String message) {
        List<String> urls = new ArrayList<>();
        if (message == null || message.isBlank()) return urls;

        for (String token : message.split("\\s+")) {
            // Strip trailing punctuation characters one at a time.
            String cleaned = stripTrailingPunctuation(token);
            String lower   = cleaned.toLowerCase();
            if (lower.startsWith("http://") || lower.startsWith("https://")
                    || lower.startsWith("www.")) {
                // Prefix www. links with https:// so host extraction is uniform.
                if (lower.startsWith("www.")) {
                    cleaned = "https://" + cleaned;
                }
                urls.add(cleaned);
            }
        }
        return urls;
    }

    /** Removes trailing . , ) ; ! ? characters from a token. */
    private String stripTrailingPunctuation(String token) {
        // Loop removes one character at a time from the right.
        while (!token.isEmpty()) {
            char last = token.charAt(token.length() - 1);
            if (last == '.' || last == ',' || last == ')' || last == ';'
                    || last == '!' || last == '?') {
                token = token.substring(0, token.length() - 1);
            } else {
                break;
            }
        }
        return token;
    }

    /**
     * Extracts the host from a URL string.
     * Splits on "//" to get everything after the scheme, then takes the first
     * "/" segment to isolate the host (removes port numbers too).
     * Returns null if the URL is malformed.
     */
    private String extractHost(String url) {
        try {
            // Split on "://" to drop the scheme, then take the part before the first "/".
            String withoutScheme = url.contains("://")
                    ? url.split("://", 2)[1]
                    : url;
            String host = withoutScheme.split("/")[0].toLowerCase();
            // Remove port number if present (e.g. "example.com:8080" -> "example.com").
            if (host.contains(":")) {
                host = host.split(":")[0];
            }
            return host.isBlank() ? null : host;
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Per-URL checks
    // -----------------------------------------------------------------------

    /** Check 1: non-HTTPS link. 20 points. */
    private void checkNonHttps(String url, List<Finding> out) {
        if (url.toLowerCase().startsWith("http://")) {
            out.add(new Finding("NON_HTTPS", 20,
                    "Link uses insecure HTTP (not HTTPS): " + url));
        }
    }

    /**
     * Check 2: raw IP address as host. 35 points.
     * Regex: \\d+ means "one or more digits".
     * The full pattern matches four groups of digits separated by dots.
     */
    private void checkRawIp(String host, List<Finding> out) {
        // Matches patterns like 192.168.1.1 — four digit groups separated by dots.
        if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            out.add(new Finding("RAW_IP_ADDRESS", 35,
                    "Link points to a raw IP address instead of a domain name: " + host));
        }
    }

    /** Check 3: known URL shortener. 30 points. */
    private void checkShortener(String host, List<Finding> out) {
        if (SHORTENERS.contains(host)) {
            out.add(new Finding("URL_SHORTENER", 30,
                    "Link uses a URL shortener (" + host + ") that hides the real destination."));
        }
    }

    /** Check 4: suspicious TLD. 25 points. */
    private void checkSuspiciousTld(String host, List<Finding> out) {
        for (String tld : BAD_TLDS) {
            if (host.endsWith(tld)) {
                out.add(new Finding("SUSPICIOUS_TLD", 25,
                        "Domain '" + host + "' uses a TLD (" + tld + ") commonly used in scams."));
                return; // flag once per host
            }
        }
    }

    /**
     * Check 5: lookalike domain. 40 points.
     * Normalizes digit-for-letter substitutions (0->o, 1->l, 3->e, 5->s),
     * then checks if the result contains a protected brand name while the
     * REAL domain is not an official domain for that brand.
     */
    private void checkLookalikeDomain(String host, List<Finding> out) {
        // Strip subdomains: take only the last two dot-separated parts.
        String baseDomain = baseDomain(host);

        // Normalize common digit-for-letter swaps in the full host.
        String normalized = host
                .replace('0', 'o')
                .replace('1', 'l')
                .replace('3', 'e')
                .replace('5', 's');

        for (String brand : PROTECTED_BRANDS) {
            if (normalized.contains(brand) && !OFFICIAL_DOMAINS.contains(baseDomain)) {
                out.add(new Finding("LOOKALIKE_DOMAIN", 40,
                        "Domain '" + host + "' appears to impersonate '" + brand
                                + "' but is not an official domain."));
                return; // one finding per host
            }
        }
    }

    /**
     * Check 6: very new domain. 40 points.
     * Uses the DomainAgeService (mock — no network calls).
     */
    private void checkDomainAge(String host, List<Finding> out) {
        String base = baseDomain(host);
        int ageDays = domainAgeService.getDomainAgeDays(base);
        if (ageDays < NEW_DOMAIN_THRESHOLD_DAYS) {
            out.add(new Finding("VERY_NEW_DOMAIN", 40,
                    "Domain '" + base + "' appears to be only " + ageDays
                            + " day(s) old (simulated). Very new domains are often used in scams."));
        }
    }

    /**
     * Returns the base domain (last two dot-separated parts) of a host.
     * e.g. "sub.paypa1-secure.xyz" -> "paypa1-secure.xyz"
     */
    private String baseDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
