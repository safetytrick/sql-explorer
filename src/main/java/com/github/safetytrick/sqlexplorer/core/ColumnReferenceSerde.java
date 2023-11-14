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
public class ColumnReferenceSerde {

    static class Serializer extends StdSerializer<ColumnReference> {

        public Serializer() {
            super(ColumnReference.class);
        }

        protected Serializer(Class<ColumnReference> t) {
            super(t);
        }

        @Override
        public void serialize(ColumnReference value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.getNormalizedReference());
        }
    }

    static class Deserializer extends StdDeserializer<ColumnReference> {

        public Deserializer() {
            super(ColumnReference.class);
        }

        protected Deserializer(Class<?> vc) {
            super(vc);
        }

        @Override
        public ColumnReference deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            return ColumnReference.fromString(p.getText());
        }
    }

    @Bean
    public com.fasterxml.jackson.databind.Module getColumnReferenceModule() {
        SimpleModule module = new SimpleModule("ColumnReferenceSerde");
        module.addDeserializer(ColumnReference.class, new Deserializer(ColumnReference.class));
        module.addSerializer(ColumnReference.class, new Serializer(ColumnReference.class));

        return module;
    }

}
