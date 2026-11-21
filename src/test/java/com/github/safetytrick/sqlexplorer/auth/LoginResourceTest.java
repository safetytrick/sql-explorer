package com.github.safetytrick.sqlexplorer.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginResourceTest {

    @Test
    void keepsAPathOnThisSite() {
        assertThat(LoginResource.safeReturn("/queries?view=graph&tags=a,b")).isEqualTo("/queries?view=graph&tags=a,b");
        assertThat(LoginResource.safeReturn("/table/app_main/account")).isEqualTo("/table/app_main/account");
    }

    @Test
    void refusesAnythingThatCouldLeaveIt() {
        assertThat(LoginResource.safeReturn(null)).isEqualTo("/");
        assertThat(LoginResource.safeReturn("")).isEqualTo("/");
        assertThat(LoginResource.safeReturn("https://elsewhere.example.com/")).isEqualTo("/");
        assertThat(LoginResource.safeReturn("//elsewhere.example.com/")).isEqualTo("/");
        assertThat(LoginResource.safeReturn("/\\elsewhere.example.com/")).isEqualTo("/");
        assertThat(LoginResource.safeReturn("queries")).isEqualTo("/");
        assertThat(LoginResource.safeReturn("/queries\r\nSet-Cookie: x=y")).isEqualTo("/");
    }
}
