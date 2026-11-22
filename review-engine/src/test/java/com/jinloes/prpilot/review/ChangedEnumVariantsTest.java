package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChangedEnumVariantsTest {

    @Nested
    class Extract {

        @Test
        void extractsAddedJavaEnumConstantsEvenWhenEnumHeaderIsInContextLines() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/OrderStatus.java b/src/OrderStatus.java
                            --- a/src/OrderStatus.java
                            +++ b/src/OrderStatus.java
                            @@ -1,5 +1,6 @@
                             public enum OrderStatus {
                                 PENDING("p"),
                                 PAID("d"),
                            +    REFUNDED("r");
                            -    PAID("d");
                             }
                            """);

            List<ChangedEnumVariants.Addition> additions = ChangedEnumVariants.extract(manifest);

            assertThat(additions)
                    .containsExactly(
                            new ChangedEnumVariants.Addition(
                                    "OrderStatus", "src/OrderStatus.java", List.of("REFUNDED")));
        }

        @Test
        void ignoresMethodAdditionsAfterJavaEnumConstantTerminator() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/OrderStatus.java b/src/OrderStatus.java
                            --- a/src/OrderStatus.java
                            +++ b/src/OrderStatus.java
                            @@ -1,6 +1,8 @@
                             public enum OrderStatus {
                                 PENDING,
                                 PAID;
                            +    public boolean isTerminal() {
                            +        return this == PAID;
                            +    }
                             }
                            """);

            assertThat(ChangedEnumVariants.extract(manifest)).isEmpty();
        }

        @Test
        void extractsAddedProtoPythonTypeScriptUnionAndSealedSubtypes() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/api/payment.proto b/api/payment.proto
                            --- a/api/payment.proto
                            +++ b/api/payment.proto
                            @@ -1,4 +1,5 @@
                             enum PaymentState {
                               PAYMENT_STATE_UNSPECIFIED = 0;
                               PAYMENT_STATE_CAPTURED = 1;
                            +  PAYMENT_STATE_DISPUTED = 2;
                             }
                            diff --git a/app/state.py b/app/state.py
                            --- a/app/state.py
                            +++ b/app/state.py
                            @@ -1,3 +1,4 @@
                             class JobPhase(StrEnum):
                                 QUEUED = "queued"
                            +    CANCELLED = "cancelled"
                            diff --git a/web/types.ts b/web/types.ts
                            --- a/web/types.ts
                            +++ b/web/types.ts
                            @@ -1,3 +1,4 @@
                             export type SyncState =
                               | 'idle'
                               | 'running'
                            +  | 'paused';
                            diff --git a/src/PaymentEvent.java b/src/PaymentEvent.java
                            --- a/src/PaymentEvent.java
                            +++ b/src/PaymentEvent.java
                            @@ -1,3 +1,4 @@
                             public sealed interface PaymentEvent {
                                 record Captured(String id) implements PaymentEvent {}
                            +    record Chargeback(String id) implements PaymentEvent {}
                             }
                            """);

            List<ChangedEnumVariants.Addition> additions = ChangedEnumVariants.extract(manifest);

            assertThat(additions)
                    .containsExactly(
                            new ChangedEnumVariants.Addition(
                                    "PaymentState",
                                    "api/payment.proto",
                                    List.of("PAYMENT_STATE_DISPUTED")),
                            new ChangedEnumVariants.Addition(
                                    "JobPhase", "app/state.py", List.of("CANCELLED")),
                            new ChangedEnumVariants.Addition(
                                    "SyncState", "web/types.ts", List.of("paused")),
                            new ChangedEnumVariants.Addition(
                                    "PaymentEvent",
                                    "src/PaymentEvent.java",
                                    List.of("Chargeback")));
        }

        @Test
        void skipsTestFilesAndPureReorderingOfExistingConstants() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/test/java/FixtureModeTest.java b/src/test/java/FixtureModeTest.java
                            --- a/src/test/java/FixtureModeTest.java
                            +++ b/src/test/java/FixtureModeTest.java
                            @@ -1,3 +1,4 @@
                             enum FixtureMode {
                                 FAST,
                            +    SLOW
                             }
                            diff --git a/src/Mode.java b/src/Mode.java
                            --- a/src/Mode.java
                            +++ b/src/Mode.java
                            @@ -1,4 +1,4 @@
                             enum Mode {
                            -    BETA,
                                 ALPHA,
                            +    BETA
                             }
                            """);

            assertThat(ChangedEnumVariants.extract(manifest)).isEmpty();
        }
    }
}
