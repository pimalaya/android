package org.pimalaya;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The spec scenarios an end-to-end test covers, each as
 * `capability: Requirement / Scenario` with the names cairn/spec/ gives them,
 * which is how the run's report maps a test onto the spec.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface E2eScenario {
    String[] value();
}
