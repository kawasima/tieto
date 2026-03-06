package net.unit8.tieto.example;

import net.unit8.tieto.spring.EnableTietoRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableTietoRepositories(basePackages = "net.unit8.tieto.example.domain")
public class SpringExampleApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringExampleApp.class, args);
    }
}
