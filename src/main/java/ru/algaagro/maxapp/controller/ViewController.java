package ru.algaagro.maxapp.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @GetMapping("/")
    public String root() {
        return "redirect:/miniapp/";
    }

    @GetMapping("/miniapp")
    public ResponseEntity<Resource> miniApp() {
        return indexResponse();
    }

    @GetMapping("/miniapp/")
    public ResponseEntity<Resource> miniAppWithSlash() {
        return indexResponse();
    }

    @GetMapping("/miniapp/index.html")
    public ResponseEntity<Resource> miniAppIndex() {
        return indexResponse();
    }

    private ResponseEntity<Resource> indexResponse() {
        Resource resource = new ClassPathResource("static/miniapp/index.html");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                .body(resource);
    }
}
