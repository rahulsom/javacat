package io.github.pulpogato.test;

import javax.json.Json;
import javax.json.JsonPatch;
import javax.json.JsonValue;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class TestUtils {
    public static <T> T parseAndCompare(Class<T> clazz, String input) throws JsonProcessingException {
        var objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        T webhookPing = objectMapper.readValue(input, clazz);
        String generated = objectMapper.enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(webhookPing);

        JsonPatch diff = Json.createDiff(
                Json.createReader(new StringReader(input)).readValue().asJsonObject(),
                Json.createReader(new StringReader(generated)).readValue().asJsonObject()
        );

        var changes = diff.toJsonArray()
                .stream()
                .map(JsonValue::asJsonObject)
                .filter(jo -> !jo.getString("op").equals("add") || !jo.get("value").toString().equals("null"))
                .toList();

        assertThat(changes).isEmpty();
        return webhookPing;
    }
}
