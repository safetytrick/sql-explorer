package com.github.safetytrick.sqlexplorer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;




@Configuration
public class SpecificRecordBaseSerde {

    abstract static class JacksonSpecificRecordBaseMixin {
        @JsonIgnore
        abstract void getClassSchema();

        @JsonIgnore
        abstract void getSpecificData();

        @JsonIgnore
        abstract void get();

        @JsonIgnore
        abstract void getSchema();
    }

    @Bean
    public com.fasterxml.jackson.databind.Module getSpecificRecordBaseModule() {
        SimpleModule module = new SimpleModule();
        module.setMixInAnnotation(SqlProfile.class, JacksonSpecificRecordBaseMixin.class);
        module.setMixInAnnotation(RuntimeMetrics.class, JacksonSpecificRecordBaseMixin.class);
        return module;
    }
}
