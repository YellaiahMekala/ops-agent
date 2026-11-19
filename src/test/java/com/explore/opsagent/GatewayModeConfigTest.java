// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Release-blocking (ADR-0003): the default configuration is gateway mode and names no provider. If someone puts a
 * direct provider URL or a provider key reference back into {@code application.yml}, the build fails. The direct path
 * lives in {@code application-direct.yml}, a profile for local development.
 */
@DisplayName("Gateway mode: the default config has no direct provider URL or key reference (ADR-0003)")
class GatewayModeConfigTest {

    static final Path DEFAULT = Path.of("src/main/resources/application.yml");

    @Test
    @DisplayName("application.yml defaults to mode gateway")
    void defaultIsGateway() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(DEFAULT));
        Properties p = yaml.getObject();
        assertEquals("${OPSAGENT_MODEL_MODE:gateway}", p.getProperty("opsagent.model.mode"));
    }

    @Test
    @DisplayName("application.yml has no provider URL, no provider key reference, no model base-url or api-key")
    void noDirectProvider() throws IOException {
        String text = Files.readString(DEFAULT).toLowerCase(Locale.ROOT);
        for (String forbidden : new String[] {"openai", "api.anthropic", "generativelanguage", "api.mistral",
                "bedrock-runtime", "azure.com", "api-key"}) {
            assertFalse(text.contains(forbidden), "application.yml must not contain '" + forbidden + "'");
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(DEFAULT));
        Properties p = yaml.getObject();
        assertFalse(p.containsKey("opsagent.model.api-key"));
        assertFalse(p.containsKey("opsagent.model.base-url"));
    }

    @Test
    @DisplayName("the deploy files give ops-agent no provider key")
    void deployHasNoProviderKey() throws IOException {
        for (String f : new String[] {"deploy/compose.yaml", "deploy/k8s/ops-agent-networkpolicy.yaml"}) {
            Path path = Path.of(f);
            if (Files.exists(path)) {
                String text = Files.readString(path);
                // Comments may explain the removal; only a service environment entry is a key reference.
                for (String line : text.split("\n")) {
                    String code = line.strip();
                    if (code.startsWith("#")) {
                        continue;
                    }
                    assertFalse(code.contains("OPENAI_API_KEY"), f + " passes a provider key: " + code);
                }
            }
        }
    }
}
