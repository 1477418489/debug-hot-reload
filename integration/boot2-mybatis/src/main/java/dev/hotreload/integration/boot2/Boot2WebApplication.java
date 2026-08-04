package dev.hotreload.integration.boot2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal Spring Boot 2.7 web fixture driven over stdin, mirroring the
 * PlainMyBatisApplication fixture protocol (HOTRELOAD_FIXTURE lines).
 */
@SpringBootApplication
public class Boot2WebApplication {
    private static final String PREFIX = "HOTRELOAD_FIXTURE ";

    public static void main(String[] args) throws Exception {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("server.port", 0);
        properties.put("spring.main.banner-mode", "off");
        SpringApplication application = new SpringApplication(Boot2WebApplication.class);
        application.setDefaultProperties(properties);
        ConfigurableApplicationContext context = application.run(args);
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();

        System.out.println(PREFIX + "READY");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String command = line.trim();
            if ("PORT".equals(command)) {
                System.out.println(PREFIX + "PORT=" + port);
            } else if ("STOP".equals(command)) {
                context.close();
                System.out.println(PREFIX + "STOPPED");
                return;
            } else if (!command.isEmpty()) {
                System.out.println(PREFIX + command + "=unknown");
            }
        }
    }
}
