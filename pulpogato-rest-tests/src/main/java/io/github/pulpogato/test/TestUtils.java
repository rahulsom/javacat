package io.github.pulpogato.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import javax.json.*;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class TestUtils {
    public static <T> T parseAndCompare(Class<T> clazz, String input) throws JsonProcessingException {
        var objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        T parsed = objectMapper.readValue(input, clazz);
        String generated = objectMapper.enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(parsed);

        JsonValue source = Json.createReader(new StringReader(input)).readValue();
        JsonValue target = Json.createReader(new StringReader(generated)).readValue();

        JsonPatch diff;
        if (source instanceof JsonObject && target instanceof JsonObject) {
            diff = Json.createDiff(source.asJsonObject(), target.asJsonObject());
        } else if (source instanceof JsonArray && target instanceof JsonArray) {
            diff = Json.createDiff(source.asJsonArray(), target.asJsonArray());
        } else {
            fail("Invalid input");
            return parsed;
        }

        var changes = diff.toJsonArray()
                .stream()
                .map(JsonValue::asJsonObject)
                .filter(jo -> !jo.getString("op").equals("add") || !jo.get("value").toString().equals("null"))
                .toList();

        assertThat(changes).isEmpty();
        return parsed;
    }
}
