package com.jinloes.prpilot.services.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StreamDtoTest {

    @Nested
    class StreamEventTest {

        @Test
        void type_returnsValue() {
            StreamEvent event = new StreamEvent();
            event.setType("assistant");
            assertThat(event.type()).isEqualTo("assistant");
        }

        @Test
        void message_presentWhenSet() {
            StreamEvent event = new StreamEvent();
            EventMessage msg = new EventMessage();
            event.setMessage(msg);
            assertThat(event.message()).contains(msg);
        }

        @Test
        void message_emptyWhenNull() {
            StreamEvent event = new StreamEvent();
            assertThat(event.message()).isEmpty();
        }

        @Test
        void result_presentWhenSet() {
            StreamEvent event = new StreamEvent();
            event.setResult("the result");
            assertThat(event.result()).contains("the result");
        }

        @Test
        void result_emptyWhenNull() {
            StreamEvent event = new StreamEvent();
            assertThat(event.result()).isEmpty();
        }
    }

    @Nested
    class EventMessageTest {

        @Test
        void content_presentWhenSet() {
            EventMessage msg = new EventMessage();
            ContentBlock block = new ContentBlock();
            msg.setContent(List.of(block));
            assertThat(msg.content()).contains(List.of(block));
        }

        @Test
        void content_emptyWhenNull() {
            EventMessage msg = new EventMessage();
            assertThat(msg.content()).isEmpty();
        }
    }

    @Nested
    class ContentBlockTest {

        @Test
        void type_returnsValue() {
            ContentBlock block = new ContentBlock();
            block.setType("tool_use");
            assertThat(block.type()).isEqualTo("tool_use");
        }

        @Test
        void name_presentWhenSet() {
            ContentBlock block = new ContentBlock();
            block.setName("mcp__github__get_file");
            assertThat(block.name()).contains("mcp__github__get_file");
        }

        @Test
        void name_emptyWhenNull() {
            ContentBlock block = new ContentBlock();
            assertThat(block.name()).isEmpty();
        }

        @Test
        void input_presentWhenSet() {
            ContentBlock block = new ContentBlock();
            Map<String, Object> input = Map.of("owner", "alice");
            block.setInput(input);
            assertThat(block.input()).contains(input);
        }

        @Test
        void input_emptyWhenNull() {
            ContentBlock block = new ContentBlock();
            assertThat(block.input()).isEmpty();
        }
    }
}
