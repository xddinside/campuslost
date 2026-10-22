package com.lostfound.handover;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class HandoverConfig {

    @Bean
    public Clock handoverClock() {
        return Clock.systemUTC();
    }

    @Bean
    public HandoverCodeGenerator handoverCodeGenerator() {
        return new SecureRandomHandoverCodeGenerator();
    }
}
