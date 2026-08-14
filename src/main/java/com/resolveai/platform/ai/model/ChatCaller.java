package com.resolveai.platform.ai.model;

import com.resolveai.platform.ai.model.LlmExceptions.LlmParseException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmUnavailableException;
import com.resolveai.platform.ai.prompt.PromptVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * One prompt, one provider, one typed answer — plus the repair attempt.
 *
 * <h2>Structured output is a parsing problem wearing a schema</h2>
 *
 * <p>Asking a model for JSON and handing the result to Jackson works most of the time,
 * and "most of the time" is the problem: at a few percent failure and a few thousand
 * tickets a month, something is unparseable every day. The failure is also not random —
 * it clusters on the unusual tickets, which are the ones where a classification would
 * have been most useful.
 *
 * <p>So there are three lines of defence, in order of cost:
 *
 * <ol>
 *   <li><b>Ask properly.</b> {@code BeanOutputConverter} puts the JSON schema in the
 *       prompt and the provider's own JSON mode enforces the syntax.
 *   <li><b>Repair once.</b> On a conversion failure, the malformed output and the schema
 *       go back with an instruction to fix it. One attempt, not a loop — a model that
 *       cannot produce the shape twice will not produce it on the fifth try either, and
 *       each attempt is real money.
 *   <li><b>Fail typed.</b> {@link LlmParseException} carries the raw output, so the
 *       {@code ai_analysis} row records what was actually said rather than "failed".
 * </ol>
 *
 * <h2>Temperature zero, and a pinned seed</h2>
 *
 * <p>Read from the prompt version's {@code params}, not hard-coded, because they are
 * part of what makes a classification reproducible and therefore belong with the prompt
 * they were chosen for. Without them the eval suite measures sampling noise: a
 * "regression" between two runs of the same prompt is indistinguishable from bad luck,
 * and there is no way to tell whether a change helped.
 */
@Component
public class ChatCaller {

    private static final Logger log = LoggerFactory.getLogger(ChatCaller.class);

    /**
     * @param attempt 1 when the model got it right first time, 2 after a repair. Recorded
     *                because a tenant whose classifications routinely need repairing has
     *                a prompt problem, and nothing else would surface it.
     */
    public record Completion<T>(T value, String modelId, int tokensIn, int tokensOut,
                                long latencyMs, int attempt) {
    }

    private final ChatClient chatClient;

    public ChatCaller(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public <T> Completion<T> complete(PromptVersion prompt, String userText, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String rendered = prompt.getTemplate().replace("{ticket}", userText);
        long startedAt = System.nanoTime();

        ChatResponse response = send(prompt, rendered + "\n\n" + converter.getFormat());
        String raw = textOf(response);

        try {
            return completion(converter.convert(raw), prompt, response, startedAt, 1);
        } catch (RuntimeException firstFailure) {
            log.warn("Model {} returned unparseable output for {}; attempting one repair",
                    prompt.getModelId(), prompt.label());

            // The repair prompt contains the malformed output and the schema, and says
            // what was wrong with it. It does NOT contain the original ticket text: the
            // model does not need to re-read the ticket to fix its own JSON, and sending
            // personal data twice to fix a syntax error is a poor trade.
            String repairPrompt = """
                    The following was supposed to be JSON matching the schema below, and \
                    could not be parsed. Return only the corrected JSON — no prose, no \
                    code fences, no explanation.

                    MALFORMED OUTPUT:
                    %s

                    REQUIRED SCHEMA:
                    %s
                    """.formatted(raw, converter.getFormat());

            ChatResponse repaired = send(prompt, repairPrompt);
            String repairedRaw = textOf(repaired);
            try {
                return completion(converter.convert(repairedRaw), prompt, repaired, startedAt, 2);
            } catch (RuntimeException secondFailure) {
                // Two failures is the end of it. A third attempt costs money and, against
                // a temperature-zero model, produces the same output.
                throw new LlmParseException(
                        "Model " + prompt.getModelId() + " could not produce valid "
                        + type.getSimpleName() + " after a repair attempt",
                        repairedRaw, secondFailure);
            }
        }
    }

    private ChatResponse send(PromptVersion prompt, String text) {
        try {
            ChatResponse response = chatClient.prompt()
                    .options(optionsFor(prompt))
                    .user(text)
                    .call()
                    .chatResponse();
            if (response == null) {
                throw new LlmUnavailableException("Provider returned no response body");
            }
            return response;
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // Wrapped so the router sees one type for "could not reach the model",
            // whatever the client library decided to throw this week.
            throw new LlmUnavailableException("Provider call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Temperature and seed from the prompt version's {@code params}.
     *
     * <p>Parsed leniently: a params blob that a newer version of the code wrote with
     * extra keys must not stop an older row being usable, and a missing temperature is
     * better served by zero than by a refusal.
     */
    private OpenAiChatOptions.Builder optionsFor(PromptVersion prompt) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(prompt.getModelId())
                .temperature(0.0);
        String params = prompt.getParams();
        if (params != null && params.contains("\"seed\"")) {
            String digits = params.replaceAll(".*\"seed\"\\s*:\\s*(\\d+).*", "$1");
            try {
                options.seed(Integer.parseInt(digits));
            } catch (NumberFormatException ignored) {
                log.debug("Prompt {} has an unreadable seed; leaving it to the provider",
                        prompt.label());
            }
        }
        // Returned as a builder, not built: ChatClient.options() takes the builder in
        // Spring AI 2.x so that the client can merge its own defaults underneath.
        return options;
    }

    private <T> Completion<T> completion(T value, PromptVersion prompt, ChatResponse response,
                                         long startedAt, int attempt) {
        var usage = response.getMetadata().getUsage();
        return new Completion<>(
                value,
                modelOf(response, prompt),
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null
                        ? 0 : usage.getCompletionTokens(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                attempt);
    }

    /**
     * The model that actually answered, from the response — not the one that was asked
     * for.
     *
     * <p>Providers alias and silently upgrade model names, so the requested id and the
     * serving id routinely differ. The cost table and the audit trail both want the one
     * that ran.
     */
    private static String modelOf(ChatResponse response, PromptVersion prompt) {
        String served = response.getMetadata().getModel();
        return served == null || served.isBlank() ? prompt.getModelId() : served;
    }

    private static String textOf(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LlmUnavailableException("Provider returned an empty completion");
        }
        return response.getResult().getOutput().getText();
    }
}
