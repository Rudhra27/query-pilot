package com.rudhra.querypilot.security;

public class UnsafeSqlException extends RuntimeException {

    public UnsafeSqlException(String message) {
        super(message);
    }
}