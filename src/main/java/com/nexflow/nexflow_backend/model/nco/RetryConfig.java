package com.nexflow.nexflow_backend.model.nco;

import lombok.Data;

/**
 * Per-node retry configuration, stored inside FlowNode.config JSON under the "retry" key.
 *
 * <pre>
 * {
 *   "retry": {
 *     "maxRetries": 3,
 *     "backoffMs": 2000,
 *     "backoffMultiplier": 2.0
 *   },
 *   ... other config ...
 * }
 * </pre>
 */
@Data
public class RetryConfig {

    /**
     * How many times to retry after the initial attempt.
     * 0 means no retry. Values are clamped to [0, 10] by the engine.
     */
    private int maxRetries = 0;

    /**
     * Delay in milliseconds before the first retry attempt.
     */
    private long backoffMs = 1000L;

    /**
     * Multiplier applied to the delay after each failed attempt.
     * e.g. 1000ms with 2.0 → 1s, 2s, 4s...
     */
    private double backoffMultiplier = 2.0d;
}

