package com.github.safetytrick.sqlexplorer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

// Scheduling is enabled but nothing is scheduled unless an environment
// declares a pullCron — see ScheduledPullService (sqlx-ijz).
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@EnableWebMvc
public class SqlExplorerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlExplorerApplication.class, args);
    }

}
