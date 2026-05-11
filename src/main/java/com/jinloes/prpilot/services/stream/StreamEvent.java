package com.jinloes.prpilot.services.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

@Data
@Getter(AccessLevel.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamEvent {

    private String type;
    private String subtype;

    @JsonProperty("is_error")
    private boolean isError;

    private EventMessage message;
    private String result;

    public String type() {
        return type;
    }

    public String subtype() {
        return subtype;
    }

    public boolean isError() {
        return isError;
    }

    public Optional<EventMessage> message() {
        return Optional.ofNullable(message);
    }

    public Optional<String> result() {
        return Optional.ofNullable(result);
    }
}
