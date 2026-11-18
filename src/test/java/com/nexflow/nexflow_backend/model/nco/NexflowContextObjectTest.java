package com.nexflow.nexflow_backend.model.nco;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NexflowContextObjectTest {

    @Test
    @SuppressWarnings("unchecked")
    void scriptLoopIndexMatchesTemplateLoopIndex() {
        NexflowContextObject nco = NexflowContextObject.create(UUID.randomUUID().toString(), "exec-1", null);
        LoopState state = new LoopState("loop-1");
        state.setIndex(1); // the loop has just started its first pass
        nco.getLoopStates().put("loop-1", state);

        Map<String, Object> input = (Map<String, Object>) nco.toScriptData().get("input");
        assertThat((Map<String, Object>) input.get("loop")).containsEntry("index", 0).containsEntry("iteration", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void noLoopKeyOutsideALoop() {
        NexflowContextObject nco = NexflowContextObject.create(UUID.randomUUID().toString(), "exec-1", null);
        Map<String, Object> input = (Map<String, Object>) nco.toScriptData().get("input");
        assertThat(input).doesNotContainKey("loop");
    }
}
