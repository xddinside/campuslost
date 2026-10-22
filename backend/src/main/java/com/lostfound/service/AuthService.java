package com.lostfound.service;

import com.lostfound.dto.*;
import com.lostfound.exception.ApiException;
import com.lostfound.model.Role;
import com.lostfound.model.User;
import com.lostfound.repository.UserRepository;
import com.lostfound.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE",
                    "An account with this email already exists");
        }
        User user = new User();
        user.setName(request.name().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        String phone = request.phone() == null ? null : request.phone().trim();
        user.setPhone(phone == null || phone.isEmpty() ? null : phone);
        user.setRole(Role.USER);
        userRepository.save(user);
        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                        "Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Invalid email or password");
        }
        return toAuthResponse(user);
    }

    public UserDto me(User user) {
        return toUserDto(user);
    }

    private AuthResponse toAuthResponse(User user) {
        return new AuthResponse(jwtService.generate(user), toUserDto(user));
    }

    private UserDto toUserDto(User user) {
        return new UserDto(user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.getCreatedAt());
    }
}
