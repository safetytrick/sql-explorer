package com.github.safetytrick.sqlexplorer.auth;

import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User currentUser() {
        return userRepository.findByEmail("admin").orElseThrow();
    }

}
