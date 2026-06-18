package com.voltx.evgenee.service;

import com.voltx.evgenee.dto.requests.LoginRequest;
import com.voltx.evgenee.dto.requests.UserRequestDto;
import com.voltx.evgenee.dto.responses.LoginResponse;
import com.voltx.evgenee.dto.responses.UserResponseDto;

public interface UserService {

    LoginResponse register(UserRequestDto requestDto);

    LoginResponse login(LoginRequest request);

    UserResponseDto getProfile(String email);

    UserResponseDto updateProfile(String email, UserRequestDto requestDto);

    void logout();

    void forgotPassword(String email);

    boolean verifyOTP(String email, String otp);

    void resetPassword(String email, String otp, String password);
}
