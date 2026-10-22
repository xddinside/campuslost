package com.lostfound.dto;

import com.lostfound.model.Role;

import java.time.Instant;

public record UserDto(
        Long id,
        String name,
        String email,
        String phone,
        Role role,
        Instant createdAt
) {
}
