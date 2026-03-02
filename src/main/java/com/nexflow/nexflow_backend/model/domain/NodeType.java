package com.nexflow.nexflow_backend.model.domain;

public enum NodeType {
    // Core nodes
    START,
    NEXUS,       // HTTP call (inline url/method/body or saved Nexus Connector REST/JDBC)
    SUB_FLOW,    // calls another flow — SYNC (waits, result available) or ASYNC (fire & forget)
    SCRIPT,      // user-written JavaScript or Python
    VARIABLE,
    MAPPER,
    DECISION,
    LOOP,      // intentional loop control node
    SUCCESS,
    FAILURE,
    AI,        // LLM node — uses LlmProviderConfig API keys, returns JSON

    // Future nodes
    KAFKA_PRODUCER,
    KAFKA_CONSUMER,
    DELAY,
    TRANSFORM
}
