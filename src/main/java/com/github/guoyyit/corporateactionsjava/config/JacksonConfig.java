package com.github.guoyyit.corporateactionsjava.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer plainBigDecimalCustomizer() {
        return builder -> builder.featuresToEnable(
                SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
