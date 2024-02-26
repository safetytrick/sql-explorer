package com.github.safetytrick.sqlexplorer.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserResource {

    private final UserService userService;

    public UserResource(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/api/sql-explorer/user")
    public User getCurrentUser() {
        return userService.currentUser();
    }

}
