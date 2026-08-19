package com.example.servicea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/call-b")
public class CallBController {

    private static final Logger log = LoggerFactory.getLogger(CallBController.class);
    private final RestTemplate restTemplate;

    public CallBController(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    @GetMapping
    public String callB() {
        log.info("Calling service-b");
        String response = restTemplate.getForObject(
                "http://localhost:8081/hello",
                String.class
        );
        log.info("Response from service-b: {}", response);
        return response;
    }
}
