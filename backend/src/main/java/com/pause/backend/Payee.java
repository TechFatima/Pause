package com.pause.backend;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Payee represents a person or business that a user has paid before.
 * Knowing the user's known payees lets Pause detect when money is going
 * somewhere new, which is a common sign of a scam.
 */
@Entity
public class Payee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The user this payee belongs to.
    private Long userId;

    // The display name of the payee, e.g. "Grocery Store".
    private String name;

    // No-args constructor required by JPA and JSON serialization.
    public Payee() {}

    // -----------------------------------------------------------------------
    // Getters and setters
    // -----------------------------------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
