package com.github.safetytrick.sqlexplorer.spring;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
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
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new JacksonJsonHttpMessageConverter(jsonMapper));
        converters.add(new StringHttpMessageConverter());
    }
}
