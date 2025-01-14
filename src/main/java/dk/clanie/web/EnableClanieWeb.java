package dk.clanie.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import dk.clanie.core.EnableClanieCore;

/**
 * Enables common web configuration and components.
 * 
 * Also enables core components (ie. implictly also @EnableClanieCore).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Configuration
@EnableClanieCore
@Import(ClanieWebConfig.class)
public @interface EnableClanieWeb {

}