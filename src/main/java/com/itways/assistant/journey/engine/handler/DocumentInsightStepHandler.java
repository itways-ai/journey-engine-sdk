package com.itways.assistant.journey.engine.handler;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiWrappedFile;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.AiConfigProvider;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentInsightStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final AiService aiService;
    private final AiConfigProvider aiConfigProvider;

    @Override
    public String getType() {
        return "DOCUMENT_INSIGHT";
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());

            String strategy     = config.getStrategy()     != null ? config.getStrategy()     : "OCR_DETAILED";
            String languageHint = config.getLanguageHint() != null ? config.getLanguageHint() : "auto";
            String pages        = config.getPages()        != null ? config.getPages()        : "all";
            boolean autoExtract = config.getAutoExtract()  == null || config.getAutoExtract();

            List<AiWrappedFile> files = (List<AiWrappedFile>) context.getVariable("files");
            if (files == null || files.isEmpty()) {
                log.warn("⚠️ Document Insight Step '{}': no files found in context", step.getStepName());
                return StepResult.error("DOCUMENT_INSIGHT: no files found in context. Ask the user to attach files in chat before this step runs.");
            }

            String query = config.getQuery() != null && !config.getQuery().isBlank()
                    ? engineUtils.replacePlaceholders(config.getQuery(), context.getVariables())
                    : null;

            String systemPrompt = buildSystemPrompt(strategy, languageHint, pages, autoExtract);
            String userPrompt   = buildUserPrompt(query, autoExtract, context.getVariables());

            log.info("👁️ Document Insight Step '{}' — strategy={}, files={}, pages={}, lang={}",
                    step.getStepName(), strategy, files.size(), pages, languageHint);

            AiRequestConfig aiRequestConfig = aiConfigProvider.getConfig(context.getAccountId());

            AiChatRequest chatRequest = AiChatRequest.builder()
                    .messages(List.of(AiMessage.system(systemPrompt), AiMessage.user(userPrompt)))
                    .files(files)
                    .config(aiRequestConfig)
                    .build();

            AiResponse response = aiService.chat(chatRequest);
            String extracted = response.getContent();

            String safeName = engineUtils.sanitizeKey(step.getStepName());
            context.addStepResult(step.getStepOrder(), extracted);
            context.setVariable(safeName, extracted);
            context.setVariable("document_insight", extracted);
            context.setVariable("step" + step.getStepOrder(), extracted);
            context.setVariable("lastStep", extracted);

            log.info("✅ Document Insight Step '{}' complete ({} chars extracted)", step.getStepName(),
                    extracted != null ? extracted.length() : 0);

            return StepResult.success(extracted,
                    step.getMessage() != null && !step.getMessage().isEmpty()
                            ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                            : "Document processed successfully using " + strategy);

        } catch (Exception e) {
            log.error("❌ Document Insight Step '{}' failed", step.getStepName(), e);
            return StepResult.error("DOCUMENT_INSIGHT failed: " + e.getMessage());
        }
    }

    private String buildSystemPrompt(String strategy, String languageHint, String pages, boolean autoExtract) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a document analysis AI. ");

        switch (strategy.toUpperCase()) {
            case "OCR_DETAILED" ->
                sb.append("Perform a thorough OCR extraction preserving all text, numbers, tables, and structure. ");
            case "OCR_FAST" ->
                sb.append("Perform a fast OCR extraction focusing on key readable text. ");
            case "FINANCE" ->
                sb.append("Extract financial data: amounts, dates, invoice numbers, parties, totals, line items, and tax. ");
            case "ID_CARD" ->
                sb.append("Extract identity fields: full name, ID number, date of birth, expiry date, nationality, and address. ");
            case "CONTRACT" ->
                sb.append("Extract contract metadata: parties, effective date, termination date, obligations, and key clauses. ");
            default ->
                sb.append("Extract all relevant information from the document. ");
        }

        if (!"all".equalsIgnoreCase(pages)) {
            sb.append("Focus on pages: ").append(pages).append(". ");
        }
        if (!"auto".equalsIgnoreCase(languageHint)) {
            sb.append("The document language is ").append(languageHint).append(". ");
        }
        if (autoExtract) {
            sb.append("Return a structured JSON object with all extracted fields.");
        } else {
            sb.append("Answer the user's specific question about the document.");
        }

        return sb.toString();
    }

    private String buildUserPrompt(String query, boolean autoExtract, Map<String, Object> variables) {
        if (query != null) {
            return query;
        }
        if (autoExtract) {
            return "Please analyze this document and extract all key information as structured JSON.";
        }
        String text = variables.containsKey("text") ? (String) variables.get("text") : null;
        return text != null && !text.isBlank()
                ? "Based on the document, answer: " + text
                : "Please analyze this document and provide a detailed summary.";
    }
}

