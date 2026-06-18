package com.voltx.evgenee.controller;

import com.voltx.evgenee.dto.requests.LoginRequest;
import com.voltx.evgenee.dto.requests.UserRequestDto;
import com.voltx.evgenee.dto.responses.ApiResponse;
import com.voltx.evgenee.dto.responses.LoginResponse;
import com.voltx.evgenee.dto.responses.UserResponseDto;
import com.voltx.evgenee.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(
            @RequestBody UserRequestDto requestDto) {

        return ResponseEntity.ok(userService.register(requestDto));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest request) {

        return ResponseEntity.ok(userService.login(request));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponseDto>> getProfile() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(ApiResponse.ok(userService.getProfile(email)));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponseDto>> updateProfile(
            @RequestBody UserRequestDto requestDto) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(ApiResponse.ok(userService.updateProfile(email, requestDto)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        userService.logout();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestBody Map<String, String> body) {
        userService.forgotPassword(body.get("email"));
        return ResponseEntity.ok(ApiResponse.<Void>ok("Password reset OTP sent", null));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Boolean>> verifyOTP(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(userService.verifyOTP(body.get("email"), body.get("otp"))));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody Map<String, String> body) {
        userService.resetPassword(body.get("email"), body.get("otp"), body.get("password"));
        return ResponseEntity.ok(ApiResponse.<Void>ok("Password reset successfully", null));
    }
}
