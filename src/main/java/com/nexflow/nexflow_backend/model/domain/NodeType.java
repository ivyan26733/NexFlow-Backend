package com.nexflow.nexflow_backend.model.domain;

public enum NodeType {
    // Core nodes
    START,
    PULSE,       // outbound HTTP call (rename display label to "HTTP Call" — done in frontend)
    NEXUS,       // uses a saved Nexus Connector (REST or JDBC)
    SUB_FLOW,    // calls another flow — SYNC (waits, result available) or ASYNC (fire & forget)
    SCRIPT,      // user-written JavaScript or Python
    VARIABLE,
    MAPPER,
    DECISION,
    SUCCESS,
    FAILURE,

    // Future nodes
    KAFKA_PRODUCER,
    KAFKA_CONSUMER,
    DELAY,
    TRANSFORM
}
