package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorContractsTest {

    @Test
    void externalIdHasStableCanonicalValue() {
        var id = new ExternalId("ruoyi5", "user", "10086");

        assertEquals("ruoyi5:user:10086", id.canonicalValue());
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExternalId("ruoyi5", "user", " ")
        );
    }

    @Test
    void userSnapshotDefensivelyCopiesRelationsAndAttributes() {
        List<ExternalId> departments = new ArrayList<>();
        departments.add(new ExternalId("generic", "department", "engineering"));
        Set<String> roles = new HashSet<>(Set.of("approver"));
        Map<String, String> attributes = new HashMap<>(Map.of("locale", "zh-CN"));

        var user = new UserSnapshot(
            new ExternalId("generic", "user", "u-1"),
            "u-1",
            "审批用户",
            null,
            null,
            true,
            departments,
            roles,
            Set.of("manager"),
            null,
            attributes
        );

        departments.clear();
        roles.clear();
        attributes.clear();

        assertEquals(1, user.departmentIds().size());
        assertEquals(Set.of("approver"), user.roleCodes());
        assertEquals(Map.of("locale", "zh-CN"), user.attributes());
        assertThrows(
            UnsupportedOperationException.class,
            () -> user.attributes().put("modified", "true")
        );
    }

    @Test
    void connectorPaginationIsBounded() {
        assertEquals(100, PageRequest.first(100).size());
        assertThrows(IllegalArgumentException.class, () -> PageRequest.first(0));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.first(501));
    }
}
