package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDefinitionJacksonSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalHttpJsonConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(ApprovalHttpJsonConfiguration.class);

    @Test
    void bootHttpMapperRoundTripsEveryClosedNodeKindAndMatchesPersistence() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var definition = everyNodeKind();
            String json = mapper.writeValueAsString(definition);
            var tree = mapper.readTree(json);
            var kinds = new java.util.HashSet<String>();
            for (var node : tree.path("nodes")) kinds.add(node.path("kind").asString());
            assertEquals(Set.of("START", "APPROVAL", "HANDLE", "CONDITION",
                "PARALLEL_SPLIT", "PARALLEL_JOIN", "END"), kinds);
            assertEquals(definition, mapper.readValue(json, ApprovalDefinition.class));
            var persistence = ApprovalDefinitionJacksonSupport.configure(new ObjectMapper());
            assertEquals(definition, persistence.readValue(json, ApprovalDefinition.class));
            assertEquals(definition, mapper.readValue(persistence.writeValueAsString(definition),
                ApprovalDefinition.class));
        });
    }

    @Test
    void actualHttpConverterAcceptsReadBackDefinitionAndRejectsUnknownDiscriminator() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var mvc = MockMvcBuilders.standaloneSetup(new EchoController())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper)).build();
            String json = mapper.writeValueAsString(everyNodeKind());
            String response = mvc.perform(post("/definition").contentType(MediaType.APPLICATION_JSON)
                    .content(json)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(everyNodeKind(), mapper.readValue(response, ApprovalDefinition.class));
            mvc.perform(post("/definition").contentType(MediaType.APPLICATION_JSON)
                    .content(json.replace("\"kind\":\"START\"", "\"kind\":\"java.lang.Runtime\"")))
                .andExpect(status().isBadRequest());
        });
    }

    @Test
    void unconfiguredJackson3ReproducesMissingKindAndCannotReadTheReturnedDefinition() {
        var mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(PurchasePaymentTemplate.processDefinition());
        assertFalse(mapper.readTree(json).path("nodes").get(0).has("kind"));
        assertThrows(tools.jackson.databind.exc.InvalidDefinitionException.class,
            () -> mapper.readValue(json, ApprovalDefinition.class));
    }

    private static ApprovalDefinition everyNodeKind() {
        var template = PurchasePaymentTemplate.processDefinition();
        var nodes = new ArrayList<>(template.nodes());
        nodes.add(new ApprovalDefinition.ParallelSplitNode("split", "Split", List.of(
            new ApprovalDefinition.ParallelBranch("a", "A", "end"),
            new ApprovalDefinition.ParallelBranch("b", "B", "end")), "join"));
        nodes.add(new ApprovalDefinition.ParallelJoinNode("join", "Join", "end"));
        return new ApprovalDefinition(template.schemaVersion(), template.definitionKey(), template.version(),
            template.name(), template.startNodeId(), nodes);
    }

    @RestController
    static class EchoController {
        @PostMapping("/definition")
        ApprovalDefinition echo(@RequestBody ApprovalDefinition definition) {
            return definition;
        }
    }
}
