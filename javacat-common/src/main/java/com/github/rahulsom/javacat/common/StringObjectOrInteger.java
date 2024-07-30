package com.github.rahulsom.javacat.common;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

@JsonDeserialize(using = StringObjectOrInteger.CustomDeserializer.class)
@JsonSerialize(using = StringObjectOrInteger.CustomSerializer.class)
@Getter
@Setter
@Accessors(chain = true, fluent = false)
public class StringObjectOrInteger {
    private String stringValue;
    private Long integerValue;
    private Map<String, Object> objectValue;

    public static class CustomDeserializer extends FancyDeserializer<StringObjectOrInteger> {
        public CustomDeserializer() {
            super(StringObjectOrInteger.class, StringObjectOrInteger::new, Mode.oneOf, List.of(
                    new SettableField<>(String.class, StringObjectOrInteger::setStringValue),
                    new SettableField<>(Long.class, StringObjectOrInteger::setIntegerValue),
                    new SettableField<>(Map.class, StringObjectOrInteger::setObjectValue)
            ));
        }
    }

    public static class CustomSerializer extends FancySerializer<StringObjectOrInteger> {
        public CustomSerializer() {
            super(StringObjectOrInteger.class, Mode.oneOf, List.of(
                    new GettableField<>(String.class, StringObjectOrInteger::getStringValue),
                    new GettableField<>(Long.class, StringObjectOrInteger::getIntegerValue),
                    new GettableField<>(Map.class, StringObjectOrInteger::getObjectValue)
            ));
        }
    }
}
