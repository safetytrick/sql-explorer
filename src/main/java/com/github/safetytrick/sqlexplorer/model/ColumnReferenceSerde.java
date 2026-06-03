package com.github.safetytrick.sqlexplorer.model;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;

@Configuration
class ColumnReferenceSerde {

    static class Serializer extends StdSerializer<ColumnReference> {

        public Serializer() {
            super(ColumnReference.class);
        }

        protected Serializer(Class<ColumnReference> t) {
            super(t);
        }

        @Override
        public void serialize(ColumnReference value, JsonGenerator gen, SerializationContext provider) {
            gen.writeString(value.toStringNormalized());
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
        public ColumnReference deserialize(JsonParser p, DeserializationContext ctxt) {
            return ColumnReference.fromString(p.getString());
        }
    }

    @Bean
    public JacksonModule getColumnReferenceModule() {
        SimpleModule module = new SimpleModule("ColumnReferenceSerde");
        module.addDeserializer(ColumnReference.class, new Deserializer(ColumnReference.class));
        module.addSerializer(ColumnReference.class, new Serializer(ColumnReference.class));

        return module;
    }

}
