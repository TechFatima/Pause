package com.pause.backend;

import jakarta.validation.constraints.NotBlank;

/** Request body for a trusted contact's one-time action. */
public class TrustedContactActionRequest {

    @NotBlank
    private String token;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
