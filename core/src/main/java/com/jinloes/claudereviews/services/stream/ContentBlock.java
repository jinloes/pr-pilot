package com.jinloes.claudereviews.services.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

@Data
@Getter(AccessLevel.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContentBlock {

    private String type;
    private String name;
    private Map<String, Object> input;
    private String text;
    private String thinking;

    public String type() {
        return type;
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public Optional<Map<String, Object>> input() {
        return Optional.ofNullable(input);
    }

    public Optional<String> text() {
        return Optional.ofNullable(text);
    }

    public Optional<String> thinking() {
        return Optional.ofNullable(thinking);
    }
}
