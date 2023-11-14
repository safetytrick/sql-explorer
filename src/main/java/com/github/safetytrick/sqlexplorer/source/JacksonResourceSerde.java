package com.github.safetytrick.sqlexplorer.source;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

@Configuration
public class JacksonResourceSerde {
    static class Serializer extends StdSerializer<Resource> {
        public Serializer() {
            this(Resource.class);
        }

        protected Serializer(Class<Resource> t) {
            super(t);
        }

        @Override
        public void serialize(Resource resource, JsonGenerator gen, SerializerProvider provider) throws IOException {
            String value = resource.isFile() ? resource.getURL().toString() : resource.getDescription();
            gen.writeString(value);
        }
    }

    @Bean
    public com.fasterxml.jackson.databind.Module jacksonReferenceModule() {
        SimpleModule module = new SimpleModule("JacksonResourceSerde");
        module.addSerializer(Resource.class, new Serializer(Resource.class));

        return module;
    }
}
