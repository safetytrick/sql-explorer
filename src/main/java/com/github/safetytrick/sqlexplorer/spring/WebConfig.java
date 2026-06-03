package com.github.safetytrick.sqlexplorer.spring;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.cfg.DateTimeFeature;

@Configuration
@EnableWebMvc
class WebConfig implements WebMvcConfigurer {

    private final List<JacksonModule> jacksonModules;
    private final Environment environment;

    public WebConfig(List<JacksonModule> jacksonModules, Environment environment) {
        this.jacksonModules = jacksonModules;
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
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.setUseSuffixPatternMatch(false);
    }


    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.modules(jacksonModules);
        builder.featuresToDisable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
        builder.findModulesViaServiceLoader(true);

        converters.add(new MappingJackson2HttpMessageConverter(builder.build()));
        converters.add(new StringHttpMessageConverter());
    }
}
