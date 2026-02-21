package com.nexflow.nexflow_backend.model.domain;

public enum NodeType {
    // MVP nodes
    START,
    PULSE,
    VARIABLE,
    MAPPER,
    DECISION,
    SUCCESS,
    FAILURE,

    // Future nodes — engine supports them via registry, not yet implemented
    KAFKA_PRODUCER,
    KAFKA_CONSUMER,
    SQL_SCRIPT,
    DELAY,
    TRANSFORM
}
