package com.electromart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the ElectroMart backend.
 *
 * <p>The application serves the existing static frontend (HTML/CSS/JS/Pictures) from the
 * same origin as the API so that the browser code can call {@code /api/...} directly.</p>
 */
@SpringBootApplication
public class ElectroMartApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElectroMartApplication.class, args);
    }
}
