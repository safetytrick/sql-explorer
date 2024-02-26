package com.github.safetytrick.sqlexplorer.spring;

import java.util.List;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableWebMvc
class WebConfig implements WebMvcConfigurer {

    private final List<Module> jacksonModules;

    public WebConfig(List<Module> jacksonModules) {
        this.jacksonModules = jacksonModules;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedMethods("GET", "POST","PUT", "DELETE");
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.setUseSuffixPatternMatch(false);
    }


    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.modules(jacksonModules);
        builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        builder.findModulesViaServiceLoader(true);

        converters.add(new MappingJackson2HttpMessageConverter(builder.build()));
        converters.add(new StringHttpMessageConverter());
    }
}
