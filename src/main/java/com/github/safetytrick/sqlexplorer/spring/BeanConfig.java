package com.github.safetytrick.sqlexplorer.spring;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class BeanConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
