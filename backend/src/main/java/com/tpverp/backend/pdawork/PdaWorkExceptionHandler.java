package com.tpverp.backend.pdawork;
import jakarta.persistence.OptimisticLockException;import java.util.Map;import org.springframework.http.*;import org.springframework.orm.ObjectOptimisticLockingFailureException;import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes={PdaWorkController.class,PdaInventoryTraceController.class})
public class PdaWorkExceptionHandler {
 @ExceptionHandler({PdaWorkConflictException.class,ObjectOptimisticLockingFailureException.class,OptimisticLockException.class})
 public ResponseEntity<Map<String,String>> conflict(Exception error){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","pda_concurrent_update","message",error.getMessage()==null?"La operación fue modificada por otro dispositivo":error.getMessage()));}
}