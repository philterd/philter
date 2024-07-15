package ai.philterd.philter;

import ai.philterd.phileas.model.configuration.PhileasConfiguration;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.io.IOException;

@PropertySource(value="file:philter.properties")
@SpringBootApplication(exclude = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
@Configuration
public class PhilterApplication {

    private static final Logger LOGGER = LogManager.getLogger(PhilterApplication.class);

    public static void main(String[] args) {

        LOGGER.info("Starting Philter...");
        SpringApplication.run(PhilterApplication.class, args);

    }

    @Bean
    public Gson gson() {
        return new Gson();
    }

    @Bean
    public PhileasConfiguration phileasConfiguration() throws IOException {
        return new PhileasConfiguration("philter.properties", "Philter");
    }

}
