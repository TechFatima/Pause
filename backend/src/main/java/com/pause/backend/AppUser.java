package com.pause.backend;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;

/**
 * AppUser represents one user of the Pause app.
 *
 * Spring/JPA concept - @Entity: this annotation tells JPA (the database layer)
 * "this class is a database table". Each field becomes a column automatically.
 *
 * Spring/JPA concept - @Id + @GeneratedValue: @Id marks which field is the
 * primary key (the unique row number). @GeneratedValue tells H2 to assign the
 * number automatically — you never set it yourself.
 */
@Entity
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    // Maximum amount allowed for a single payment (in USD).
    private BigDecimal maxPerPayment;

    // Maximum total allowed across all payments in a 7-day window (in USD).
    private BigDecimal weeklyCap;

    // If true, any payment to a payee the user has never paid before needs
    // approval from the trusted contact.
    private boolean newPayeesNeedApproval;

    // Name of the trusted contact who receives alerts (e.g. "Sam").
    private String trustedContactName;

    // -----------------------------------------------------------------------
    // No-args constructor required by JPA and JSON serialization.
    // JPA needs to create a blank object before filling in the fields.
    // -----------------------------------------------------------------------
    public AppUser() {}

    // -----------------------------------------------------------------------
    // Getters and setters
    // -----------------------------------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getMaxPerPayment() { return maxPerPayment; }
    public void setMaxPerPayment(BigDecimal maxPerPayment) { this.maxPerPayment = maxPerPayment; }

    public BigDecimal getWeeklyCap() { return weeklyCap; }
    public void setWeeklyCap(BigDecimal weeklyCap) { this.weeklyCap = weeklyCap; }

    public boolean isNewPayeesNeedApproval() { return newPayeesNeedApproval; }
    public void setNewPayeesNeedApproval(boolean newPayeesNeedApproval) {
        this.newPayeesNeedApproval = newPayeesNeedApproval;
    }

    public String getTrustedContactName() { return trustedContactName; }
    public void setTrustedContactName(String trustedContactName) {
        this.trustedContactName = trustedContactName;
    }
}
