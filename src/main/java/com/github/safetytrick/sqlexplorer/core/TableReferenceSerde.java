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
public class TableReferenceSerde {

    static class Serializer extends StdSerializer<TableReference> {

        public Serializer() {
            super(TableReference.class);
        }

        protected Serializer(Class<TableReference> t) {
            super(t);
        }

        @Override
        public void serialize(TableReference value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.getNormalizedName());
        }
    }

    static class Deserializer extends StdDeserializer<TableReference> {

        public Deserializer() {
            super(TableReference.class);
        }

        protected Deserializer(Class<?> vc) {
            super(vc);
        }

        @Override
        public TableReference deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            return TableReference.fromString(p.getText());
        }
    }

    @Bean
    public com.fasterxml.jackson.databind.Module getTableReferenceModule() {
        SimpleModule module = new SimpleModule("TableReferenceSerde");
        module.addDeserializer(TableReference.class, new Deserializer(TableReference.class));
        module.addSerializer(TableReference.class, new Serializer(TableReference.class));

        return module;
    }
}
