package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LineCommentSuggestedChangeTest {

    @Nested
    class GetSuggestedChange {

        @Test
        void defaultsToEmptyWhenUnset() {
            assertThat(new LineComment().getSuggestedChange()).isEmpty();
            assertThat(new LineComment("a.java", 1, "issue", "body").getSuggestedChange())
                    .isEmpty();
        }

        @Test
        void nullBecomesEmpty() {
            LineComment comment = new LineComment();
            comment.setSuggestedChange("x");
            comment.setSuggestedChange(null);

            assertThat(comment.getSuggestedChange()).isEmpty();
        }

        @Test
        void returnsValueVerbatim() {
            LineComment comment = new LineComment();
            comment.setSuggestedChange("    return a + b;\n    // done");

            assertThat(comment.getSuggestedChange()).isEqualTo("    return a + b;\n    // done");
        }
    }

    @Nested
    class BeanProperty {

        // Jackson binds plain bean properties, so a readable and writable descriptor is what makes
        // the field round-trip; a legacy document without it leaves the setter uncalled.
        @Test
        void isReadableAndWritableBeanProperty() throws IntrospectionException {
            PropertyDescriptor descriptor =
                    Arrays.stream(
                                    Introspector.getBeanInfo(LineComment.class)
                                            .getPropertyDescriptors())
                            .filter(p -> p.getName().equals("suggestedChange"))
                            .findFirst()
                            .orElseThrow();

            assertThat(descriptor.getReadMethod()).isNotNull();
            assertThat(descriptor.getWriteMethod()).isNotNull();
            assertThat(descriptor.getPropertyType()).isEqualTo(String.class);
        }
    }
}
