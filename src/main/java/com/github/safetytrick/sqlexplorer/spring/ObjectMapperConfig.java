package com.github.safetytrick.sqlexplorer.spring;

import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
class ObjectMapperConfig {

    @Primary
    @Bean
    public ObjectMapper objectMapper(List<com.fasterxml.jackson.databind.Module> modules) {
        return new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .registerModules(modules)
            .findAndRegisterModules();
    }


}
