package com.workingdead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling  
@SpringBootApplication
public class WorkingdeadApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkingdeadApplication.class, args);
    }
}
