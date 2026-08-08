package com.github.safetytrick.sqlexplorer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.module.SimpleModule;
import com.github.safetytrick.sqlexplorer.avro.RepeatedBlock;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;


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
    public JacksonModule getSpecificRecordBaseModule() {
        SimpleModule module = new SimpleModule();
        module.setMixInAnnotation(SqlProfile.class, JacksonSpecificRecordBaseMixin.class);
        module.setMixInAnnotation(RuntimeMetrics.class, JacksonSpecificRecordBaseMixin.class);
        module.setMixInAnnotation(RepeatedBlock.class, JacksonSpecificRecordBaseMixin.class);
        return module;
    }
}
