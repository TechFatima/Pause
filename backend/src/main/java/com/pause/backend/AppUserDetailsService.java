package com.pause.backend;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public AppUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser user = userRepository.findByEmailIgnoreCase(email)
                .filter(found -> found.getPasswordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));

        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities("USER")
                .build();
    }
}
