package com.explore.opsagent.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What an alert must look like before anything acts on it (plan 12.1). The alert body becomes model input, so it is
 * bounded here: names and label values are identifiers, annotations are short, counts are capped. Problems are named
 * by field, never by echoing the value.
 */
public final class AlertValidation {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern LABEL_VALUE = Pattern.compile("[A-Za-z0-9._:/@=+-]{0,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{1,32}");
    private static final int MAX_ANNOTATION = 512;
    private static final int MAX_LABELS = 32;

    private AlertValidation() {
    }

    public static List<String> problems(AlertmanagerWebhook body, int maxAlerts) {
        List<String> problems = new ArrayList<>();
        if (body == null) {
            return List.of("body: missing");
        }
        if (!"4".equals(body.version())) {
            problems.add("version: only Alertmanager webhook version 4 is accepted");
        }
        if (body.alerts() == null || body.alerts().isEmpty()) {
            problems.add("alerts: none");
            return problems;
        }
        if (body.alerts().size() > maxAlerts) {
            problems.add("alerts: more than " + maxAlerts);
            return problems;
        }
        for (int i = 0; i < body.alerts().size(); i++) {
            AlertmanagerWebhook.Alert a = body.alerts().get(i);
            String at = "alerts[" + i + "].";
            if (a.labels() == null || a.name() == null || !NAME.matcher(a.name()).matches()) {
                problems.add(at + "labels.alertname: missing or not a name");
                continue;
            }
            if (!"firing".equals(a.status()) && !"resolved".equals(a.status())) {
                problems.add(at + "status: not firing or resolved");
            }
            if (a.fingerprint() == null || !FINGERPRINT.matcher(a.fingerprint()).matches()) {
                problems.add(at + "fingerprint: not a fingerprint");
            }
            if (a.startsAt() == null) {
                problems.add(at + "startsAt: missing");
            }
            if (a.labels().size() > MAX_LABELS) {
                problems.add(at + "labels: too many");
            }
            for (Map.Entry<String, String> l : a.labels().entrySet()) {
                if (!NAME.matcher(l.getKey()).matches() || l.getValue() == null
                        || !LABEL_VALUE.matcher(l.getValue()).matches()) {
                    problems.add(at + "labels." + safeKey(l.getKey()) + ": not an identifier");
                }
            }
            if (a.annotations() != null) {
                for (Map.Entry<String, String> n : a.annotations().entrySet()) {
                    if (!NAME.matcher(n.getKey()).matches() || n.getValue() == null
                            || n.getValue().length() > MAX_ANNOTATION) {
                        problems.add(at + "annotations." + safeKey(n.getKey()) + ": too long or unnamed");
                    }
                }
            }
        }
        return problems;
    }

    private static String safeKey(String key) {
        return key != null && NAME.matcher(key).matches() ? key : "?";
    }
}
