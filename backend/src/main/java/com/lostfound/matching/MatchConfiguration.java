package com.lostfound.matching;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the bounded retry task owned by the matching feature. */
@Configuration
@EnableScheduling
public class MatchConfiguration {
}
