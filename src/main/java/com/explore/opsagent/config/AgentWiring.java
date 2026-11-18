package com.explore.opsagent.config;

import com.explore.opsagent.tools.OpsTools;
import com.explore.opsagent.tools.ReadOnlyHttp;
import com.explore.opsagent.tools.ToolAudit;
import com.explore.opsagent.tools.ToolPolicy;
import com.explore.opsagent.triage.RunRecovery;
import com.explore.opsagent.triage.TriageMailer;
import com.explore.opsagent.triage.TriageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Builds the agent explicitly: the model, the tools, the policy and the limits are all visible here, none of them
 * auto-configured.
 * <p>
 * The model talks to OpenAI directly, not through the gateway. The gateway does not offer tool calling (its ADR-1 and
 * the rule that tools stay out of it), and this agent's loop is tool calling; its ADR-18 records the split. The model
 * client retries nothing (the run's guardrails decide what happens next) and asks OpenAI not to store completions.
 */
@Configuration
public class AgentWiring {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    // Only with a key: an empty OPENAI_API_KEY means no model bean, and the start fails on the missing bean.
    @ConditionalOnExpression("!'${opsagent.model.api-key:}'.isBlank()")
    ChatModel openAiChatModel(OpsAgentProperties properties) {
        OpsAgentProperties.Model m = properties.model();
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(m.apiKey())
                .modelName(m.name())
                .temperature(0.0)
                .maxCompletionTokens(m.maxOutputTokens())
                .timeout(m.timeout())
                .maxRetries(0)
                .store(false)
                .parallelToolCalls(false)
                .logRequests(false)
                .logResponses(false);
        if (m.baseUrl() != null && !m.baseUrl().isBlank()) {
            builder.baseUrl(m.baseUrl());
        }
        return builder.build();
    }

    @Bean
    ReadOnlyHttp readOnlyHttp(OpsAgentProperties properties) {
        return new ReadOnlyHttp(properties.sources().httpTimeout());
    }

    @Bean
    ToolPolicy toolPolicy(OpsAgentProperties properties) {
        return new ToolPolicy(properties.rbac());
    }

    @Bean
    ToolAudit toolAudit(JdbcTemplate jdbc, Clock clock) {
        return new ToolAudit(jdbc, clock);
    }

    @Bean
    OpsTools opsTools(OpsAgentProperties properties, ReadOnlyHttp http, ToolPolicy policy, ToolAudit audit, Clock clock) {
        return new OpsTools(properties.sources(), http, policy, audit, clock);
    }

    @Bean
    TriageMailer triageMailer(JavaMailSender mail, OpsAgentProperties properties) {
        return new TriageMailer(mail, properties.mail(), properties.sources().dashboardUrl());
    }

    /** Two runs at a time, a short queue, refuse beyond it: an alert storm is answered with BUSY, not a backlog. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService triagePool(OpsAgentProperties properties) {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.webhook().queueCapacity()), Thread.ofPlatform().name("triage-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    TriageService triageService(JdbcTemplate jdbc, ChatModel model, OpsTools tools, TriageMailer mailer,
                                ExecutorService triagePool, OpsAgentProperties properties, Clock clock,
                                ObjectMapper json) {
        // Who holds a run (ADR-0001). Configured, or a fresh id per process: a restarted process is a new holder, and
        // its previous life's runs are found by their stale heartbeat like any other dead instance's.
        String configured = properties.recovery().instanceId();
        String instanceId = configured == null || configured.isBlank()
                ? "ops-agent-" + UUID.randomUUID().toString().substring(0, 8) : configured;
        return new TriageService(jdbc, model, tools, mailer, triagePool, properties, clock, json, instanceId);
    }

    @Bean(destroyMethod = "stop")
    RunRecovery runRecovery(JdbcTemplate jdbc, TriageService triageService, TriageMailer mailer,
                            OpsAgentProperties properties, Clock clock) {
        return new RunRecovery(jdbc, triageService, mailer, properties.recovery(), clock);
    }
}
