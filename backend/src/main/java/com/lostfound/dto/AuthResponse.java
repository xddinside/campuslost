package com.lostfound.dto;

public record AuthResponse(
        String token,
        UserDto user
) {
}
