package com.pause.backend;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AppUserRepository gives us standard database operations for AppUser.
 *
 * Spring/JPA concept - JpaRepository: this is an interface you extend but
 * never implement yourself. Spring sees it at startup and generates all the
 * standard operations automatically: findAll(), findById(), save(), delete(),
 * and more. You just declare the interface and Spring does the rest.
 *
 * The two type parameters are:
 *   AppUser  — the entity (table) this repository works with
 *   Long     — the type of AppUser's primary key (@Id field)
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    // No extra methods needed for now. JpaRepository already gives us everything.
}
