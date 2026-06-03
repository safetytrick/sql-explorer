package com.github.safetytrick.sqlexplorer.model;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;

@Configuration
class TableReferenceSerde {

    static class Serializer extends StdSerializer<TableReference> {

        public Serializer() {
            super(TableReference.class);
        }

        protected Serializer(Class<TableReference> t) {
            super(t);
        }

        @Override
        public void serialize(TableReference value, JsonGenerator gen, SerializationContext provider) {
            gen.writeString(value.toStringNormalized());
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
        public TableReference deserialize(JsonParser p, DeserializationContext ctxt) {
            return TableReference.fromString(p.getString());
        }
    }

    @Bean
    public JacksonModule getTableReferenceModule() {
        SimpleModule module = new SimpleModule("TableReferenceSerde");
        module.addDeserializer(TableReference.class, new Deserializer(TableReference.class));
        module.addSerializer(TableReference.class, new Serializer(TableReference.class));

        return module;
    }
}
