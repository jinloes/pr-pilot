package com.jinloes.claudereviews.services.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

@Data
@Getter(AccessLevel.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamEvent {

    private String type;
    private EventMessage message;
    private String result;

    public String type() {
        return type;
    }

    public Optional<EventMessage> message() {
        return Optional.ofNullable(message);
    }

    public Optional<String> result() {
        return Optional.ofNullable(result);
    }
}
