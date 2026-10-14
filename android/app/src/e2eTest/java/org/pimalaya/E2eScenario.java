package org.pimalaya;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The spec scenarios an end-to-end test covers, mapping it onto the spec.
 *
 * <p>Each as `capability: Requirement / Scenario`, with cairn/spec/'s names.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface E2eScenario {
    String[] value();
}
