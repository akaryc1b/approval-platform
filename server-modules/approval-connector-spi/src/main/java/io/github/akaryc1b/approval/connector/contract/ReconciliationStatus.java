package io.github.akaryc1b.approval.connector.contract;

public enum ReconciliationStatus {
    CONFIRMED_SUCCESS,
    CONFIRMED_REJECTION,
    CONFIRMED_PERMANENT_FAILURE,
    STILL_UNKNOWN,
    NOT_FOUND,
    CONFLICT
}
