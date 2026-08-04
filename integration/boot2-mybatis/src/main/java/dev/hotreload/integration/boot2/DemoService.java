package dev.hotreload.integration.boot2;

import org.springframework.stereotype.Service;

@Service
public class DemoService {
    public String value() {
        return "SVC";
    }
}
