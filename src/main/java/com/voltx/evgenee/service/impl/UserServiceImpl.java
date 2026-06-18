package com.voltx.evgenee.service.impl;

import com.voltx.evgenee.dto.requests.LoginRequest;
import com.voltx.evgenee.dto.requests.VehicleRequestDto;
import com.voltx.evgenee.dto.requests.UserRequestDto;
import com.voltx.evgenee.dto.responses.LoginResponse;
import com.voltx.evgenee.dto.responses.UserResponseDto;
import com.voltx.evgenee.dto.responses.VehicleResponseDto;
import com.voltx.evgenee.entity.EvUser;
import com.voltx.evgenee.entity.StationOwner;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.entity.Vehicle;
import com.voltx.evgenee.enums.Role;
import com.voltx.evgenee.enums.VehicleType;
import com.voltx.evgenee.exceptions.BadRequestException;
import com.voltx.evgenee.exceptions.ResourceNotFoundException;
import com.voltx.evgenee.repository.EvUserRepository;
import com.voltx.evgenee.repository.StationOwnerRepository;
import com.voltx.evgenee.repository.UserRepository;
import com.voltx.evgenee.repository.VehicleRepository;
import com.voltx.evgenee.service.UserService;
import com.voltx.evgenee.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final EvUserRepository evUserRepository;
    private final StationOwnerRepository stationOwnerRepository;
    private final VehicleRepository vehicleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional
    public LoginResponse register(UserRequestDto req) {
        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new BadRequestException("Email already registered: " + req.getEmail());
        }

        User user = new User();
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(normalizeRole(req.getRole()));

        User saved = userRepository.save(user);
        if (saved.getRole() == Role.STATION_OWNER) {
            stationOwnerRepository.save(StationOwner.builder()
                    .name(req.getName())
                    .contact("")
                    .authUser(saved)
                    .build());
        } else if (saved.getRole() == Role.EV_USER) {
            EvUser evUser = evUserRepository.save(EvUser.builder()
                    .fullName(req.getName())
                    .authUser(saved)
                    .vehicles(new ArrayList<>())
                    .build());
            syncVehicles(evUser, req);
        }

        String token = jwtUtil.generateToken(saved.getEmail(), List.of("ROLE_" + saved.getRole().name()));
        return LoginResponse.builder()
                .token(token)
                .data(toResponse(saved))
                .build();
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + request.getEmail()));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadRequestException("Invalid password");
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
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponseDto updateProfile(String email, UserRequestDto requestDto) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        if (requestDto.getEmail() != null) user.setEmail(requestDto.getEmail());
        if (requestDto.getPassword() != null) user.setPassword(passwordEncoder.encode(requestDto.getPassword()));
        if (requestDto.getRole() != null) user.setRole(normalizeRole(requestDto.getRole()));

        User saved = userRepository.save(user);
        if (saved.getRole() == Role.EV_USER) {
            EvUser evUser = evUserRepository.findByEmail(saved.getEmail())
                    .orElseGet(() -> evUserRepository.save(EvUser.builder().authUser(saved).build()));
            if (requestDto.getName() != null) evUser.setFullName(requestDto.getName());
            syncVehicles(evUser, requestDto);
            evUserRepository.save(evUser);
        } else if (saved.getRole() == Role.STATION_OWNER) {
            StationOwner owner = stationOwnerRepository.findByEmail(saved.getEmail())
                    .orElseGet(() -> stationOwnerRepository.save(StationOwner.builder().authUser(saved).build()));
            if (requestDto.getName() != null) owner.setName(requestDto.getName());
            stationOwnerRepository.save(owner);
        }
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
        return true;
    }

    @Override
    public void resetPassword(String email, String otp, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
    }

    private UserResponseDto toResponse(User user) {
        String displayRole = switch (user.getRole()) {
            case STATION_OWNER -> "StationOwner";
            case ADMIN -> "admin";
            case EV_USER -> "user";
        };

        String name = null;
        VehicleResponseDto primaryVehicle = null;
        List<VehicleResponseDto> savedVehicles = List.of();
        List<String> vehicleNumbers = List.of();

        if (user.getRole() == Role.EV_USER) {
            EvUser evUser = evUserRepository.findByEmail(user.getEmail()).orElse(null);
            if (evUser != null) {
                name = evUser.getFullName();
                List<Vehicle> vehicles = vehicleRepository.findByOwnerId(evUser.getId());
                savedVehicles = vehicles.stream().map(this::toVehicleResponse).toList();
                primaryVehicle = savedVehicles.isEmpty() ? null : savedVehicles.get(0);
                vehicleNumbers = vehicles.stream()
                        .map(Vehicle::getLicensePlate)
                        .filter(v -> v != null && !v.isBlank())
                        .toList();
            }
        } else if (user.getRole() == Role.STATION_OWNER) {
            StationOwner owner = stationOwnerRepository.findByEmail(user.getEmail()).orElse(null);
            if (owner != null) {
                name = owner.getName();
            }
        }

        return UserResponseDto.builder()
                .id(String.valueOf(user.getId()))
                ._id(String.valueOf(user.getId()))
                .name(name)
                .email(user.getEmail())
                .role(displayRole)
                .vehicle(primaryVehicle)
                .savedVehicles(savedVehicles)
                .vehicleNumbers(vehicleNumbers)
                .build();
    }

    private Role normalizeRole(String role) {
        if (role == null || role.isBlank() || role.equalsIgnoreCase("user")) {
            return Role.EV_USER;
        }
        String normalized = role.trim().replace("-", "_").replace(" ", "_").toUpperCase(Locale.ROOT);
        if (normalized.equals("STATIONOWNER")) normalized = "STATION_OWNER";
        if (normalized.equals("ADMIN")) return Role.ADMIN;
        if (normalized.equals("STATION_OWNER")) return Role.STATION_OWNER;
        return Role.EV_USER;
    }

    private void syncVehicles(EvUser evUser, UserRequestDto req) {
        List<VehicleRequestDto> requests = new ArrayList<>();
        if (req.getVehicle() != null) requests.add(req.getVehicle());
        if (req.getSavedVehicles() != null) requests.addAll(req.getSavedVehicles());
        if (requests.isEmpty() && req.getVehicleNumbers() != null) {
            for (String number : req.getVehicleNumbers()) {
                requests.add(VehicleRequestDto.builder().vehicleNumber(number).build());
            }
        }
        if (requests.isEmpty()) return;

        List<Vehicle> existing = vehicleRepository.findByOwnerId(evUser.getId());
        vehicleRepository.deleteAll(existing);
        for (VehicleRequestDto dto : requests) {
            vehicleRepository.save(Vehicle.builder()
                    .owner(evUser)
                    .model(dto.getNickname())
                    .licensePlate(dto.getVehicleNumber())
                    .connectorType(dto.getConnectorType())
                    .batteryCapacity(dto.getBatteryCapacity())
                    .type(parseVehicleType(dto.getType()))
                    .build());
        }
    }

    private VehicleType parseVehicleType(String value) {
        if (value == null || value.isBlank()) return VehicleType.EV;
        try {
            return VehicleType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return VehicleType.EV;
        }
    }

    private VehicleResponseDto toVehicleResponse(Vehicle vehicle) {
        return VehicleResponseDto.builder()
                .id(String.valueOf(vehicle.getId()))
                ._id(String.valueOf(vehicle.getId()))
                .nickname(vehicle.getModel())
                .type(vehicle.getType() != null ? vehicle.getType().name() : "EV")
                .connectorType(vehicle.getConnectorType())
                .batteryCapacity(vehicle.getBatteryCapacity())
                .vehicleNumber(vehicle.getLicensePlate())
                .build();
    }
}
