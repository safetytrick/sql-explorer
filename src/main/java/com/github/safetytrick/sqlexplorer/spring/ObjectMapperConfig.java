package com.github.safetytrick.sqlexplorer.spring;

import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JacksonModule;

@Configuration
class ObjectMapperConfig {

    @Primary
    @Bean
    public ObjectMapper objectMapper(List<JacksonModule> modules) {
        return JsonMapper.builder()
                    .addModules(modules)
                    .findAndAddModules()
                    .build();
    }


}
