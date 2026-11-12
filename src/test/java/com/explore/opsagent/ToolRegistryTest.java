package com.explore.opsagent;

import com.explore.opsagent.tools.OpsTools;
import com.explore.opsagent.tools.ReadOnlyHttp;
import com.explore.opsagent.tools.ToolPolicy;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plan 12.2, release-blocking for this repository: the agent's tool registry is read-only. If this fails, a tool that
 * can change something has been added. Remove it; do not weaken this test.
 */
@DisplayName("Tool registry is read-only (plan 12.2)")
class ToolRegistryTest {

    private static final Pattern WRITE_VERBS = Pattern.compile(
            "(?i).*(delete|remove|restart|reboot|kill|stop|start|scale|silence|mute|ack|put|post|patch|write|update|"
                    + "create|insert|set|exec|run|deploy|rollback|purge|replay|requeue|discard).*");

    private static List<String> toolNames() {
        return Arrays.stream(OpsTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class)).map(Method::getName).sorted().toList();
    }

    @Test
    @DisplayName("exactly the four documented tools exist")
    void exactlyFour() {
        assertEquals(OpsTools.TOOL_NAMES.stream().sorted().toList(), toolNames());
    }

    @Test
    @DisplayName("no tool name is a write verb")
    void noWriteVerbs() {
        for (String name : toolNames()) {
            String verb = name.replaceAll("([A-Z]).*", "");
            assertFalse(WRITE_VERBS.matcher(verb).matches(), name + " starts with a write verb");
        }
    }

    @Test
    @DisplayName("the tools' only HTTP client has one public method, get")
    void httpIsGetOnly() {
        List<String> methods = Arrays.stream(ReadOnlyHttp.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).map(Method::getName).sorted().toList();
        assertEquals(List.of("get"), methods);
    }

    @Test
    @DisplayName("the tools hold no other client: fields are the sources, the GET-only client, policy, audit, clock")
    void noOtherClients() {
        for (var field : OpsTools.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.startsWith("java.net.http.HttpClient"), field.getName());
            assertFalse(type.contains("JdbcTemplate") || type.contains("DataSource"), field.getName());
            assertFalse(type.contains("RestClient") || type.contains("WebClient"), field.getName());
        }
    }

    @Test
    @DisplayName("RBAC names only real tools; an unknown name stops the start")
    void rbacValidated() {
        assertThrows(IllegalArgumentException.class, () -> new ToolPolicy(Map.of("alertmanager", List.of("restartGateway"))));
        ToolPolicy p = new ToolPolicy(Map.of("alertmanager", List.of("readRunbook")));
        assertFalse(p.allows("alertmanager", "queryPrometheus"));
        assertFalse(p.allows("someone-else", "readRunbook"));
    }
}
