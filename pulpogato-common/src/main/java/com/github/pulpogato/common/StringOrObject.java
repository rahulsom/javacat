package com.github.pulpogato.common;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * A class that can be a String or an Object.
 */
@JsonDeserialize(using = StringOrObject.CustomDeserializer.class)
@JsonSerialize(using = StringOrObject.CustomSerializer.class)
@Getter
@Setter
@Accessors(chain = true, fluent = false)
public class StringOrObject {
    private String stringValue;
    private Map<String, Object> objectValue;

    static class CustomDeserializer extends FancyDeserializer<StringOrObject> {
        public CustomDeserializer() {
            super(StringOrObject.class, StringOrObject::new, Mode.oneOf, List.of(
                    new SettableField<>(String.class, StringOrObject::setStringValue),
                    new SettableField<>(Map.class, StringOrObject::setObjectValue)
            ));
        }
    }

    static class CustomSerializer extends FancySerializer<StringOrObject> {
        public CustomSerializer() {
            super(StringOrObject.class, Mode.oneOf, List.of(
                    new GettableField<>(String.class, StringOrObject::getStringValue),
                    new GettableField<>(Map.class, StringOrObject::getObjectValue)
            ));
        }
    }
}
