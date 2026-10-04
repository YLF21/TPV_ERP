package com.tpverp.backend.terminal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/terminal-linking")
public class TerminalLinkingController {
    private final TerminalLinkingService service;
    public TerminalLinkingController(TerminalLinkingService service) { this.service = service; }

    @GetMapping("/bootstrap")
    public TerminalLinkingService.Bootstrap bootstrap(@RequestParam String challenge) { return service.bootstrap(challenge); }
    @PostMapping("/requests")
    public TerminalLinkingService.LinkState request(@RequestBody TerminalLinkingService.LinkRequest request) { return service.request(request); }
    @PostMapping("/requests/status")
    public TerminalLinkingService.LinkState status(@RequestBody TerminalLinkingService.Proof request) { return service.status(request); }
    @PostMapping("/requests/cancel")
    public TerminalLinkingService.LinkState cancel(@RequestBody TerminalLinkingService.Proof request) { return service.cancel(request); }
    @PostMapping("/server/adopt")
    public TerminalLinkingService.LinkState adopt(@RequestBody TerminalLinkingService.ServerAdoption request, HttpServletRequest http) {
        return service.adoptServer(request, loopback(http), null);
    }
    @PostMapping("/legacy/adopt")
    public TerminalLinkingService.LinkState adoptLegacy(@RequestBody TerminalLinkingService.ServerAdoption request) {
        return service.adoptLegacy(request);
    }
    static boolean loopback(HttpServletRequest request) {
        // Never trust Forwarded/X-Forwarded-For supplied by callers.
        return request.getHeader("Forwarded") == null && request.getHeader("X-Forwarded-For") == null
                && Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr());
    }
}
