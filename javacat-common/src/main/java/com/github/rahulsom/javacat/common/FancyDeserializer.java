package com.github.rahulsom.javacat.common;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A deserializer that can handle <code>anyOf</code>, <code>allOf</code>, and <code>oneOf</code>.
 *
 * @param <T> The type
 */
@Slf4j
public class FancyDeserializer<T> extends StdDeserializer<T>  {

    public record SettableField<T, X>(Class<X> type, BiConsumer<T, X> setter) {}

    private static final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    public FancyDeserializer(Class<T> vc, Supplier<T> initializer, Mode mode, List<SettableField<T, ?>> fields) {
        super(vc);
        this.initializer = initializer;
        this.mode = mode;
        this.fields = fields;
    }

    private final Supplier<T> initializer;
    private final Mode mode;
    private final List<SettableField<T, ?>> fields;

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        var map = ctxt.readValue(p, Map.class);
        var string = om.writeValueAsString(map);
        var retval = initializer.get();
        fields.forEach(pair -> setField(pair, string, retval));
        return retval;
    }

    private <X> void setField(SettableField<T, X> field, String string, T retval) {
        var clazz = field.type();
        var consumer = field.setter();

        try {
            var x = parse(string, clazz);
            if (x != null) {
                consumer.accept(retval, x);
            }
        } catch (JacksonException e) {
            log.debug("Failed to parse {} as {}", string, clazz, e);
        }
    }

    private <X> X parse(String string, Class<X> clazz) throws JsonProcessingException {
        X x = om.readValue(string, clazz);
        String newString = om.writeValueAsString(x);
        if (string.equals(newString)) {
            return x;
        }
        return null;
    }

}
