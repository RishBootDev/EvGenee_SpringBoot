package com.voltx.evgenee.runner;

import com.voltx.evgenee.entity.Admin;
import com.voltx.evgenee.entity.User;
import com.voltx.evgenee.enums.Role;
import com.voltx.evgenee.repository.AdminRepository;
import com.voltx.evgenee.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultAdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.default.email:admin@evgenee.com}")
    private String adminEmail;

    @Value("${admin.default.password:Admin@123}")
    private String adminPassword;

    @Value("${admin.default.name:EvGenee Admin}")
    private String adminName;

    @Value("${admin.default.contact:}")
    private String adminContact;

    @Override
    @Transactional
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.warn("Default admin seed skipped because email or password is blank");
            return;
        }

        User user = userRepository.findByEmail(adminEmail)
                .map(existing -> {
                    if (existing.getRole() != Role.ADMIN) {
                        existing.setRole(Role.ADMIN);
                        return userRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    User created = new User();
                    created.setEmail(adminEmail);
                    created.setPassword(passwordEncoder.encode(adminPassword));
                    created.setRole(Role.ADMIN);
                    return userRepository.save(created);
                });

        adminRepository.findByAuthUserEmail(adminEmail)
                .orElseGet(() -> adminRepository.save(Admin.builder()
                        .name(adminName)
                        .contact(adminContact)
                        .authUser(user)
                        .build()));
    }
}
