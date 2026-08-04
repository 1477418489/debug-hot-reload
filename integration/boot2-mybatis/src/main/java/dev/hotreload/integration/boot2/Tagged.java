package dev.hotreload.integration.boot2;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Mirrors RuoYi-style @DataScope/@Log: an @annotation-pointcut aspect marker. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tagged {
}
