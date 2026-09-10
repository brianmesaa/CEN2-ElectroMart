package com.electromart.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record AdminLoginRequest(
        @JsonAlias({"adminUser", "username"})
        String email,
        String password
) {
}
