package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Converter
class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        return values == null ? null : JSON.writeValueAsString(values);
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        return column == null || column.isBlank() ? null : List.copyOf(JSON.readValue(column, LIST));
    }
}
