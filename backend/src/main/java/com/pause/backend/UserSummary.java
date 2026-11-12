package com.pause.backend;

import java.math.BigDecimal;

/** Safe user details returned to the signed-in browser. */
public record UserSummary(
        Long id,
        String name,
        String email,
        BigDecimal maxPerPayment,
        BigDecimal weeklyCap,
        boolean newPayeesNeedApproval
) {
    public static UserSummary from(AppUser user) {
        return new UserSummary(
                user.getId(), user.getName(), user.getEmail(), user.getMaxPerPayment(),
                user.getWeeklyCap(), user.isNewPayeesNeedApproval());
    }
}
