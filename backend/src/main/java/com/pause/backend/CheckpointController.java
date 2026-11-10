package com.pause.backend;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * CheckpointController handles POST /api/checkpoint.
 * Validation errors are caught here and returned as clean JSON 400 responses.
 */
@RestController
@RequestMapping("/api")
public class CheckpointController {

    private final CheckpointService checkpointService;

    public CheckpointController(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    /**
     * POST /api/checkpoint
     * Body: { "payee": "...", "amount": 30.00, "message": "...", "hour": 12 }
     *
     * Spring concept - @Valid: tells Spring to run Bean Validation on the request
     * body before calling this method. Invalid input throws MethodArgumentNotValidException.
     *
     * Spring concept - @RequestBody: tells Spring to parse the HTTP JSON body
     * into a CheckpointRequest object automatically.
     */
    @PostMapping("/checkpoint")
    public ResponseEntity<?> checkpoint(@Valid @RequestBody CheckpointRequest request) {
        try {
            CheckpointResult result = checkpointService.evaluate(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Unexpected error: " +
                            (ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage())));
        }
    }

    /**
     * Catches @Valid failures (e.g. blank payee, negative amount) and returns
     * a clean JSON 400 instead of Spring's default HTML error page or stack trace.
     *
     * Spring concept - @ExceptionHandler: when Spring throws an exception of
     * the given type inside this controller, Spring calls this method instead
     * of propagating the exception to the client.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationError(
            MethodArgumentNotValidException ex) {

        // Collect all field error messages into one string.
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", errors));
    }
}
