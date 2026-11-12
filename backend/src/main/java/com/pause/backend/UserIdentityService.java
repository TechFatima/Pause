package com.pause.backend;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class UserIdentityService {

    private final AppUserRepository userRepository;

    public UserIdentityService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("An authenticated user is required.");
        }
        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Signed-in account was not found."));
    }
}
