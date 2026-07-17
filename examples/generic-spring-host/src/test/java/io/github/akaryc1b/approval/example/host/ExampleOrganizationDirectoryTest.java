package io.github.akaryc1b.approval.example.host;

import io.github.akaryc1b.approval.example.host.HostContractModels.AuthenticationRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.ExternalId;
import io.github.akaryc1b.approval.example.host.HostContractModels.ManagerChainRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.PageRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExampleOrganizationDirectoryTest {

    private ExampleOrganizationDirectory directory;

    @BeforeEach
    void setUp() {
        ExampleHostProperties properties = new ExampleHostProperties();
        properties.setSource("generic");
        properties.setTenantId("demo");
        properties.setTenantName("Demo Tenant");
        properties.setBearerToken("smoke-token");
        directory = new ExampleOrganizationDirectory(
            properties,
            Clock.fixed(Instant.parse("2026-07-17T16:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void authenticatesFixturePrincipalWithoutEchoingCredential() {
        var response = directory.authenticate(new AuthenticationRequest(
            "BEARER",
            "smoke-token",
            Map.of()
        ));

        assertEquals("alice", response.principal().username());
        assertEquals("generic:tenant:demo", canonical(response.tenant().id()));
        assertEquals(Instant.parse("2026-07-17T17:00:00Z"), response.expiresAt());
        assertEquals("fixture-token", response.attributes().get("authentication"));
    }

    @Test
    void rejectsInvalidCredential() {
        GenericHostException exception = assertThrows(
            GenericHostException.class,
            () -> directory.authenticate(new AuthenticationRequest(
                "BEARER",
                "wrong-token",
                Map.of()
            ))
        );

        assertEquals(401, exception.status());
        assertEquals("INVALID_CREDENTIAL", exception.code());
    }

    @Test
    void searchesByRoleAndDepartment() {
        var page = directory.searchUsers(
            new UserQuery(
                null,
                new ExternalId("generic", "department", "20"),
                "finance-manager",
                null,
                true
            ),
            new PageRequest(0, 20, null)
        );

        assertEquals(1, page.total());
        assertEquals("dave", page.items().getFirst().username());
    }

    @Test
    void resolvesManagerChain() {
        var managers = directory.managerChain(new ManagerChainRequest(
            new ExternalId("generic", "user", "100"),
            10
        ));

        assertEquals(1, managers.size());
        assertEquals("bob", managers.getFirst().username());
    }

    @Test
    void rejectsForeignExternalIdentifiers() {
        GenericHostException exception = assertThrows(
            GenericHostException.class,
            () -> directory.findUser(new ExternalId("ruoyi5", "user", "100"))
        );

        assertEquals("INVALID_EXTERNAL_ID", exception.code());
    }

    private static String canonical(ExternalId id) {
        return id.source() + ':' + id.objectType() + ':' + id.value();
    }
}
