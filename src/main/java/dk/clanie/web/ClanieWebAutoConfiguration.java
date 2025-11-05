package dk.clanie.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@ConditionalOnClass(WebClient.class)
public class ClanieWebAutoConfiguration {


	@Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }


    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(WebClient.Builder.class)
    WebClientFactory webClientFactory(WebClient.Builder webClientBuilder) {
        return new WebClientFactory(webClientBuilder);
    }


}
