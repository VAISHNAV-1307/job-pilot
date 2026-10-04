package io.github.vaishnav.jobpilot;

import org.springframework.boot.SpringApplication;

public class TestJobPilotApplication {

    public static void main(String[] args) {
        SpringApplication.from(JobPilotApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
