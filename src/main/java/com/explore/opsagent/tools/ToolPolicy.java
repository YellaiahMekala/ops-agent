package com.explore.opsagent.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which caller may use which tool (RBAC). Configured per caller under {@code opsagent.rbac}; a caller that is not
 * configured may use nothing. A configured tool name that does not exist stops the start, so a typo cannot quietly
 * grant less (or more) than intended.
 */
public class ToolPolicy {

    private final Map<String, Set<String>> allowed;

    public ToolPolicy(Map<String, List<String>> rbac) {
        for (Map.Entry<String, List<String>> e : rbac.entrySet()) {
            for (String tool : e.getValue()) {
                if (!OpsTools.TOOL_NAMES.contains(tool)) {
                    throw new IllegalArgumentException("opsagent.rbac." + e.getKey() + " names an unknown tool: " + tool);
                }
            }
        }
        this.allowed = rbac.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    }

    public boolean allows(String caller, String tool) {
        return allowed.getOrDefault(caller, Set.of()).contains(tool);
    }
}
