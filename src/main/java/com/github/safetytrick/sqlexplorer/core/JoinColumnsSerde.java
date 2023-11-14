package com.github.safetytrick.sqlexplorer.core;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

@Configuration
public class JoinColumnsSerde {

    static class Serializer extends StdSerializer<JoinColumns> {

        public Serializer() {
            super(JoinColumns.class);
        }

        protected Serializer(Class<JoinColumns> t) {
            super(t);
        }

        @Override
        public void serialize(JoinColumns value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartArray();
            gen.writeString(value.getLeft().getNormalizedReference());
            gen.writeString(value.getRight().getNormalizedReference());
            gen.writeEndArray();
        }
    }

    static class Deserializer extends StdDeserializer<JoinColumns> {

        public Deserializer() {
            super(JoinColumns.class);
        }

        protected Deserializer(Class<?> vc) {
            super(vc);
        }

        @Override
        public JoinColumns deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            String[] cols = ctxt.readValue(p, String[].class);
            if (cols.length != 2) {
                ctxt.reportInputMismatch(this, "invalid input, expected two args: " + cols.length);
            }
            return JoinColumns.fromReferences(cols[0], cols[1]);
        }
    }

    @Bean
    public com.fasterxml.jackson.databind.Module getJoinColumnModule() {
        SimpleModule module = new SimpleModule("JoinColumnsSerde");
        module.addDeserializer(JoinColumns.class, new Deserializer(JoinColumns.class));
        module.addSerializer(JoinColumns.class, new Serializer(JoinColumns.class));

        return module;
    }
    
}
