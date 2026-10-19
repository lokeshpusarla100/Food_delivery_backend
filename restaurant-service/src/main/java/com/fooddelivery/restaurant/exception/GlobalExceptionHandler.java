package com.fooddelivery.restaurant.exception;

import com.fooddelivery.commons.dto.ApiResponse;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.validation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> domain(DomainException e){
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(),e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e){
        String names=e.getBindingResult().getFieldErrors().stream().map(FieldError::getField)
            .distinct().sorted().collect(Collectors.joining(","));
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_INPUT","Invalid fields: "+names));
    }
    @ExceptionHandler({DataIntegrityViolationException.class,DuplicateKeyException.class})
    public ResponseEntity<ApiResponse<Void>> conflict(Exception e){
        return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(ApiResponse.error("CONFLICT","A conflicting resource already exists"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> generic(Exception e){
        log.error("Unhandled Restaurant Service failure",e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ApiResponse.error("INTERNAL_ERROR","An unexpected error occurred"));
    }
}
