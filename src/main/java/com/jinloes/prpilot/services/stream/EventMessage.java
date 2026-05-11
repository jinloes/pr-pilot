package com.jinloes.prpilot.services.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

@Data
@Getter(AccessLevel.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventMessage {

    private List<ContentBlock> content;

    public Optional<List<ContentBlock>> content() {
        return Optional.ofNullable(content);
    }
}
