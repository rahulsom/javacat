package io.github.pulpogato.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * A serializer that can handle <code>anyOf</code>, <code>allOf</code>, and <code>oneOf</code>.
 *
 * @param <T> The type
 */
public class FancySerializer<T> extends StdSerializer<T> {
    /**
     * A field that can be read from the object
     *
     * @param type   The class of the object
     * @param getter The method that gets the field from the object
     * @param <T>    The type of the object
     * @param <X>    The type of the field
     */
    public record GettableField<T, X>(Class<X> type, Function<T, X> getter) {
    }

    private static final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Constructs a serializer
     *
     * @param vc     The class being serialized
     * @param mode   The mode of serialization
     * @param fields The fields that can be read from the class
     */
    public FancySerializer(Class<T> vc, Mode mode, List<GettableField<T, ?>> fields) {
        super(vc);
        this.mode = mode;
        this.fields = fields;
    }

    /**
     * The mode of serialization
     */
    private final Mode mode;

    /**
     * The fields that can be read from the class
     */
    private final List<GettableField<T, ?>> fields;

    @Override
    public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        var serialized = fields.stream()
                .map(field -> field.getter().apply(value))
                .filter(Objects::nonNull)
                .toList();

        if (mode == Mode.oneOf) {
            Object o = serialized.get(0);
            if (o instanceof String s) {
                gen.writeString(s);
            } else if (o instanceof Integer i) {
                gen.writeNumber(i);
            } else if (o instanceof Long i) {
                gen.writeNumber(i);
            } else if (o instanceof Double d) {
                gen.writeNumber(d);
            } else if (o instanceof Float d) {
                gen.writeNumber(d);
            } else if (o instanceof BigDecimal d) {
                gen.writeNumber(d);
            } else if (o instanceof BigInteger d) {
                gen.writeNumber(d);
            } else if (o instanceof Boolean b) {
                gen.writeBoolean(b);
            } else {
                gen.writeObject(o);
            }
            return;
        }

        var superMap = serialized.stream()
                .map(x -> {
                    try {
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
