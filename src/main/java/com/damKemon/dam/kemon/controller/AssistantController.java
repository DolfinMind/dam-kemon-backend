package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.AssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * "দরদাম" shopping assistant. Stateless: each call gets a {@code message} and
 * returns { reply, products[], trust{}, suggestions[], intent }. Backed by the
 * rules-based {@link AssistantService} (zero external cost). A future free-tier
 * LLM can be slotted in front of the same tool-set without changing this API.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody(required = false) Map<String, Object> body) {
        String message = body == null ? null : (body.get("message") == null ? null : body.get("message").toString());
        return ResponseEntity.ok(assistant.chat(message));
    }
}
