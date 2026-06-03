package com.github.safetytrick.sqlexplorer.spring;

import java.util.Arrays;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebMvc
class WebConfig implements WebMvcConfigurer {

    private final JsonMapper jsonMapper;
    private final Environment environment;

    public WebConfig(JsonMapper jsonMapper, Environment environment) {
        this.jsonMapper = jsonMapper;
        this.environment = environment;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
        }
    }

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.registerDefaults();
        builder.withJsonConverter(new JacksonJsonHttpMessageConverter(jsonMapper));
    }
}
