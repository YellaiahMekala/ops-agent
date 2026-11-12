package com.explore.opsagent.agent;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/** What the agent concluded. Parsed from the model's structured answer; every field is shown as the model's, unchecked. */
public record TriageReport(
        @Description("short sentences, each citing the number or log line it rests on") List<String> findings,
        @Description("one sentence, or 'unknown' when the evidence does not support a cause") String likelyCause,
        @Description("LOW, MEDIUM or HIGH") String confidence) {
}
