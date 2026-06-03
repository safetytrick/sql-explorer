package com.github.safetytrick.sqlexplorer.model;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;

@Configuration
class JoinColumnsSerde {

    static class Serializer extends StdSerializer<JoinColumns> {

        public Serializer() {
            super(JoinColumns.class);
        }

        protected Serializer(Class<JoinColumns> t) {
            super(t);
        }

        @Override
        public void serialize(JoinColumns value, JsonGenerator gen, SerializationContext provider) {
            gen.writeStartArray();
            gen.writeString(value.getLeft().toStringNormalized());
            gen.writeString(value.getRight().toStringNormalized());
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
        public JoinColumns deserialize(JsonParser p, DeserializationContext ctxt) {
            String[] cols = ctxt.readValue(p, String[].class);
            if (cols.length != 2) {
                ctxt.reportInputMismatch(this, "invalid input, expected two args: " + cols.length);
            }
            return JoinColumns.fromReferences(cols[0], cols[1]);
        }
    }

    @Bean
    public JacksonModule getJoinColumnModule() {
        SimpleModule module = new SimpleModule("JoinColumnsSerde");
        module.addDeserializer(JoinColumns.class, new Deserializer(JoinColumns.class));
        module.addSerializer(JoinColumns.class, new Serializer(JoinColumns.class));

        return module;
    }
    
}
