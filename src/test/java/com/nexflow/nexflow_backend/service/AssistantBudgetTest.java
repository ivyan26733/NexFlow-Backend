package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantBudgetTest {

    private static AssistantKnowledge knowledge() throws Exception {
        try (var in = new ClassPathResource("assistant/prompt.md").getInputStream()) {
            return new AssistantKnowledge(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void splitsKnowledgeIntoTopicAndNodeSections() throws Exception {
        AssistantKnowledge kb = knowledge();
        assertThat(kb.sections()).extracting(AssistantKnowledge.Section::title)
                .contains("SCOPE", "NODE LOOP", "NODE FILE", "NODE MESSAGE", "CHANNEL SETUP (Channels page → New channel → copy the Webhook URL)");
        assertThat(kb.full()).hasSizeGreaterThan(20_000);
    }

    @Test
    void compactKeepsTheRelevantSectionsWithinBudget() throws Exception {
        AssistantKnowledge kb = knowledge();
        String loop = kb.compact("How does the loop node repeat its body?", 4_000);
        assertThat(loop).hasSizeLessThan(4_600).contains("SCOPE").contains("8. LOOP").doesNotContain("15. MESSAGE");

        String telegram = kb.compact("How do I connect my telegram bot channel?", 4_000);
        assertThat(telegram).contains("Telegram").hasSizeLessThan(4_600);
    }

    @Test
    void readsGroqRetryAfter() {
        assertThat(AssistantService.retryAfterSeconds(
                "Groq (Fast inference) API error 429: Rate limit reached ... Please try again in 7.5s. Need more tokens?"))
                .isEqualTo(7.5);
        assertThat(AssistantService.retryAfterSeconds("API error 429: rate limit ... Please try again in 640ms")).isEqualTo(0.64);
        assertThat(AssistantService.retryAfterSeconds("API error 500: boom")).isNull();
    }

    @Test
    void explainsUsageLimits() {
        String msg = AssistantService.friendlyError(LlmProvider.GROQ,
                "Groq (Fast inference) API error 413: Request too large for model on tokens per minute (TPM): Limit 8000");
        assertThat(msg).startsWith("Groq").contains("per-minute usage limit").contains("Settings → AI Providers");
        assertThat(AssistantService.Budget.forProvider(LlmProvider.GROQ)).isEqualTo(AssistantService.Budget.SMALL);
        assertThat(AssistantService.Budget.forProvider(LlmProvider.ANTHROPIC)).isEqualTo(AssistantService.Budget.FULL);
    }
}
