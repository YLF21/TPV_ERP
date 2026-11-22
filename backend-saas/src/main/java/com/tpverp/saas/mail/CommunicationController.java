package com.tpverp.saas.mail;

import static com.tpverp.saas.mail.Communications.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/communications")
class CommunicationController {
    private final CommunicationService service;
    private final CommunicationStore store;
    private final CommunicationAudience audience;
    CommunicationController(CommunicationService service, CommunicationStore store, CommunicationAudience audience) {
        this.service = service;
        this.store = store;
        this.audience = audience;
    }

    @GetMapping Page<Summary> history(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return store.history(page,size);
    }
    @GetMapping("/audience") Page<AudienceEntry> audience(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size, @RequestParam(defaultValue="") String q) {
        return audience.page(page,size,q);
    }
    @PostMapping("/preview") Body preview(@RequestBody @Valid Html input) { return CommunicationHtml.sanitize(input.html()); }
    @PostMapping Detail create(@RequestBody @Valid Draft input) { return service.save(null,input); }
    @GetMapping("/{id}") Detail detail(@PathVariable UUID id) { return store.detail(id); }
    @PutMapping("/{id}") Detail save(@PathVariable UUID id, @RequestBody @Valid Draft input) { return service.save(id,input); }
    @PostMapping("/{id}/review") Review review(@PathVariable UUID id, @RequestBody @Valid Revision revision) {
        return service.review(id,revision.version());
    }
    @PostMapping("/{id}/send") Detail send(@PathVariable UUID id, @RequestBody @Valid Confirmation confirmation) {
        return service.send(id,confirmation);
    }
    @PostMapping("/{id}/cancel") Detail cancel(@PathVariable UUID id, @RequestBody @Valid Revision revision) {
        return service.cancel(id,revision.version());
    }
    @GetMapping("/{id}/recipients") Page<Recipient> recipients(@PathVariable UUID id,
            @RequestParam(required=false) UUID reviewId, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return store.recipients(id,reviewId,page,size);
    }
    @PostMapping("/{id}/recipients/{recipient}/retry") Detail retry(@PathVariable UUID id, @PathVariable UUID recipient,
            @RequestBody @Valid Retry retry) { return service.retry(id,recipient,retry); }
    @PostMapping("/{id}/test") @ResponseStatus(HttpStatus.NO_CONTENT)
    void test(@PathVariable UUID id, @RequestBody @Valid TestMail test) { service.test(id,test); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> badInput() { return problem(invalid()); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> problem(ResponseStatusException ex) {
        var result = ResponseEntity.status(ex.getStatusCode());
        if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) result.header("Retry-After","30");
        return result.body(ex.getBody());
    }
}
