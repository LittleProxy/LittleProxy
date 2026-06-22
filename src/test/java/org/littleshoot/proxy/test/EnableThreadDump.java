package org.littleshoot.proxy.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable thread dump generation for a test class or test method.
 *
 * <p>When present on a test class, thread dumps will be generated for all test methods in that
 * class. When present on a specific test method, thread dumps will only be generated for that
 * method.
 *
 * <p>This annotation is only effective when the {@link ThreadDumpExtension} is registered (either
 * via service loader or via {@code @ExtendWith(ThreadDumpExtension.class)}).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableThreadDump {}
