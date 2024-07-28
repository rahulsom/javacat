package com.github.rahulsom.javacat.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class FancySerializer<T> extends StdSerializer<T> {
    public record GettableField<T, X>(Class<X> type, Function<T, X> getter) {}

    private static final ObjectMapper om = new ObjectMapper();

    public FancySerializer(Class<T> vc, Mode mode, List<GettableField<T, ?>> fields) {
        super(vc);
        this.mode = mode;
        this.fields = fields;
    }

    private final Mode mode;
    private final List<GettableField<T, ?>> fields;

    @Override
    public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        var superMap = fields.stream()
                .map(field -> {
                    try {
                        var x = field.getter().apply(value);
                        if (x == null) {
                            return null;
                        }
                        String string = om.writeValueAsString(x);
                        return om.readValue(string, LinkedHashMap.class);
                    } catch (JsonProcessingException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .reduce(new LinkedHashMap<>(), (m1, m2) -> {
                    m1.putAll(m2);
                    return m1;
                });
        gen.writeObject(superMap);
    }
}
