package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;

class AgentIdentityPromptTest {

    @Test
    void statesNameWorkspaceAndCategory() {
        Agent agent = new Agent();
        agent.setName("Customer Support");
        agent.setCategory("support");
        assertThat(AgentRunner.identityLine(agent, "Demo Workspace"))
                .isEqualTo("You are Customer Support, an AI agent in Demo Workspace's AI workforce (support). "
                        + "If asked your name or who you are, say that - never invent a human name.");
    }

    @Test
    void fallsBackWhenWorkspaceNameIsUnknown() {
        Agent agent = new Agent();
        agent.setName("Research");
        assertThat(AgentRunner.identityLine(agent, null)).startsWith("You are Research, an AI agent in this AI workforce");
    }
}
