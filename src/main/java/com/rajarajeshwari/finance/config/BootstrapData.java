package com.rajarajeshwari.finance.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** On a brand-new database: creates the Admin role, a first administrator and the standard note list. */
@Component
@Order(2)
public class BootstrapData implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public BootstrapData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (count("roles") == 0) {
            jdbc.update("INSERT INTO roles(role_id, role_desc) VALUES (1, 'Admin')");
        }
        if (count("users") == 0) {
            jdbc.update("INSERT INTO users(user_id, username, password_hash, first_name, role_id) VALUES (1, 'admin', ?, 'Administrator', 1)",
                    new BCryptPasswordEncoder().encode("admin123"));
            System.out.println("FIRST RUN: created user 'admin' with password 'admin123' - please change it after logging in.");
        }
        if (count("note_types") == 0) {
            jdbc.update("INSERT INTO note_types(note_id, note) VALUES (1, 'PROMISSORY NOTE'), (2, 'VEHICLE PAPERS'), (3, 'NO')");
        }
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
