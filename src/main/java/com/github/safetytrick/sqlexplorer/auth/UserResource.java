package com.github.safetytrick.sqlexplorer.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="User")
@RestController
class UserResource {

    private final UserService userService;

    public UserResource(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/user")
    public User getCurrentUser() {
        return userService.currentUser();
    }

}
