package io.github.akaryc1b.approval.connector.generic;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.AuthenticationConnector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GenericRestAuthenticationConnector implements AuthenticationConnector {

    public static final String PATH = "/api/approval-connector/v1/authenticate";
    public static final String OPERATION = "authentication.authenticate.v1";

    private final GenericRestTransport transport;
    private final GenericRestSnapshotMapper mapper;

    public GenericRestAuthenticationConnector(GenericRestTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.mapper = new GenericRestSnapshotMapper();
    }

    @Override
    public AuthenticationResult authenticate(
        ConnectorContext context,
        AuthenticationRequest request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credentialType", request.credentialType());
        body.put("credential", request.credential());
        body.put("attributes", request.attributes());

        var response = transport.post(context, PATH, OPERATION, body);
        response.requireSuccess(OPERATION);
        return mapper.authenticationResult(response.body().path("data"));
    }
}
