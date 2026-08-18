/*
 * Copyright (C) 2025, Claus Nielsen, clausn999@gmail.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package dk.clanie.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for clanie-web.
 *
 * <p>Ordered after Boot's WebClient and RestClient auto-configurations, and by name so
 * that this module needs a compile dependency on neither. The ordering is what makes the
 * {@code @ConditionalOnBean} conditions below meaningful: a condition on a bean another
 * auto-configuration registers is only reliable once that one has run.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration",
        "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration" })
public class ClanieWebAutoConfiguration {


	@Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }


    /**
     * Only where a {@code WebClient.Builder} exists to build on.
     *
     * <p>Spring Boot 4 moved that builder's auto-configuration into its own module,
     * {@code spring-boot-webclient}, so {@code spring-boot-starter-webflux} alone no
     * longer supplies one. Declaring this bean unconditionally therefore broke any
     * consumer that has webflux but not that module - and it broke it in the worst
     * possible way: the bean is {@code @Lazy}, so nothing goes wrong until something
     * walks the whole context, which is exactly what scanning for scheduled jobs does.
     * The application then fails to start, having worked in every test that did not
     * enable the scheduler.
     *
     * <p>A consumer that wants a {@code WebClientFactory} and does not get one is
     * missing {@code spring-boot-webclient}.
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    @ConditionalOnBean(WebClient.Builder.class)
    WebClientFactory webClientFactory(WebClient.Builder webClientBuilder) {
        return new WebClientFactory(webClientBuilder);
    }


    /**
     * Only where a {@code RestClient.Builder} exists to build on - see
     * {@link #webClientFactory} for why that is not a given. Boot 4 moved this builder's
     * auto-configuration into {@code spring-boot-restclient}, so an application can have
     * Spring MVC, use {@code RestClient} freely through its static factory, and still
     * have no builder bean for this to consume.
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    @ConditionalOnBean(RestClient.Builder.class)
    RestClientFactory restClientFactory(RestClient.Builder restClientBuilder) {
        return new RestClientFactory(restClientBuilder);
    }


}
