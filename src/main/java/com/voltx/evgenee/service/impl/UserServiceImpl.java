package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.dto.requests.LoginRequest;
import com.voltx.evgenee.dto.requests.UserRequestDto;
import com.voltx.evgenee.dto.responses.LoginResponse;
import com.voltx.evgenee.dto.responses.UserResponseDto;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.enums.Role;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.service.UserService;
import com.voltx.evgenee.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public UserResponseDto register(UserRequestDto req) {
        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered: " + req.getEmail());
        }

        User user = new User();
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(Role.valueOf(req.getRole() != null ? req.getRole() : "EV_USER"));

        User saved = userRepository.save(user);

        return toResponse(saved);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found with email: " + request.getEmail()));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid password");
        }

        String token = jwtUtil.generateToken(user.getEmail(), List.of("ROLE_" + user.getRole().name()));

        return LoginResponse.builder()
                .token(token)
                .data(toResponse(user))
                .build();
    }

    @Override
    public UserResponseDto getProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
        return toResponse(user);
    }

    @Override
    public UserResponseDto updateProfile(String email, UserRequestDto requestDto) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        if (requestDto.getEmail() != null) user.setEmail(requestDto.getEmail());
        if (requestDto.getPassword() != null) user.setPassword(passwordEncoder.encode(requestDto.getPassword()));

        User saved = userRepository.save(user);
        return toResponse(saved);
    }

    @Override
    public void logout() {
    }

    @Override
    public void forgotPassword(String email) {
    }

    @Override
    public boolean verifyOTP(String email, String otp) {
        return false;
    }

    @Override
    public void resetPassword(String email, String otp, String password) {
    }

    private UserResponseDto toResponse(User user) {
        return UserResponseDto.builder()
                .id(String.valueOf(user.getId()))
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}
