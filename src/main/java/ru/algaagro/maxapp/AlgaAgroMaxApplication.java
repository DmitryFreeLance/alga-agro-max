package ru.algaagro.maxapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlgaAgroMaxApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlgaAgroMaxApplication.class, args);
    }
}
