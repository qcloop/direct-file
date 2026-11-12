package com.realworld.tax.advisor.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The server, rather than an LLM's remembered date, determines the current planning year. */
@Configuration
public class PlanningClockConfig {

    @Bean
    Clock planningClock(@Value("${tax-advisor.planning-time-zone:America/New_York}") String timeZone) {
        // A named zone makes New Year's behavior independent of the deployment host's time zone.
        // Injecting Clock also lets the calendar boundary be tested without waiting for January.
        return Clock.system(ZoneId.of(timeZone));
    }
}
