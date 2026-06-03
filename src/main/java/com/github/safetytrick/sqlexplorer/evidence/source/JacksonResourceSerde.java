package com.github.safetytrick.sqlexplorer.evidence.source;

import java.io.IOException;
import java.io.UncheckedIOException;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;

@Configuration
class JacksonResourceSerde {
    static class Serializer extends StdSerializer<Resource> {
        public Serializer() {
            this(Resource.class);
        }

        protected Serializer(Class<Resource> t) {
            super(t);
        }

        @Override
        public void serialize(Resource resource, JsonGenerator gen, SerializationContext provider) {
            String value;
            try {
                value = resource.isFile() ? resource.getURL().toString() : resource.getDescription();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            gen.writeString(value);
        }
    }

    @Bean
    public JacksonModule jacksonReferenceModule() {
        SimpleModule module = new SimpleModule("JacksonResourceSerde");
        module.addSerializer(Resource.class, new Serializer(Resource.class));

        return module;
    }
}
