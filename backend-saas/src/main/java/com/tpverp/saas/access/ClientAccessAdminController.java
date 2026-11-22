package com.tpverp.saas.access;

import static com.tpverp.saas.access.ClientAccessRequests.*;
import com.tpverp.saas.access.ClientAccessRequestQuery.Search;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class ClientAccessAdminController {
    private final ClientAccessRequestQuery query;
    private final ClientAccessReviewService review;

    public ClientAccessAdminController(ClientAccessRequestQuery query, ClientAccessReviewService review) {
        this.query = query;
        this.review = review;
    }

    @GetMapping("/client-access-requests")
    public Page<Summary> requests(@RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return query.requests(new Search(page, size, q, sort, direction), status);
    }

    @GetMapping("/client-access-requests/{id}")
    public Detail detail(@PathVariable UUID id) { return query.detail(id); }

    @PostMapping("/client-access-requests/{id}/approve")
    public Detail approve(@PathVariable UUID id, @Valid @RequestBody Approval approval) {
        return review.approve(id, approval);
    }

    @PostMapping("/client-access-requests/{id}/reject")
    public Detail reject(@PathVariable UUID id, @Valid @RequestBody Rejection rejection) {
        return review.reject(id, rejection);
    }

    @PostMapping("/client-access-requests/{id}/retry-email")
    public Detail retryEmail(@PathVariable UUID id) { return review.retryEmail(id); }

    @GetMapping("/tenant-users")
    public Page<DirectoryUser> users(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "username") String sort, @RequestParam(defaultValue = "asc") String direction) {
        return query.users(new Search(page, size, q, sort, direction));
    }

    // Spring's default validation warning includes rejected values, e.g. a staff review note.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidReview() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "La decisión contiene campos inválidos");
    }
}
