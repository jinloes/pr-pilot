package com.jinloes.prpilot.review;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Skips a test that needs a positive source-inventory traversal when the JVM lacks {@code
 * SecureDirectoryStream} (macOS JDKs before 25), where the worker correctly fails closed with
 * {@code UNSUPPORTED_API}. On macOS the build runs tests on an installed JDK 25 automatically.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(
        value = "com.jinloes.prpilot.review.SecureTraversal#supported",
        disabledReason =
                "JVM lacks SecureDirectoryStream (macOS before JDK 25); install JDK 25 to run")
public @interface RequiresSecureTraversal {}
