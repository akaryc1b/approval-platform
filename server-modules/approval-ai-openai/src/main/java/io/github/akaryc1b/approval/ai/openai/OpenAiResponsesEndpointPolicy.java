package io.github.akaryc1b.approval.ai.openai;

import java.net.URI;
import java.util.Locale;

/** Exact, non-configurable P6-D OpenAI Responses endpoint policy. */
public final class OpenAiResponsesEndpointPolicy {

    public static final String SCHEME = "https";
    public static final String HOST = "api.openai.com";
    public static final int PORT = 443;
    public static final String PATH = "/v1/responses";

    private static final URI EXACT_URI = URI.create(
        SCHEME + "://" + HOST + ":" + PORT + PATH
    );
    private static final OpenAiResponsesEndpointPolicy EXACT =
        new OpenAiResponsesEndpointPolicy(EXACT_URI);

    private final URI uri;
    private final String endpointHash;

    private OpenAiResponsesEndpointPolicy(URI uri) {
        this.uri = requireExact(uri);
        this.endpointHash = OpenAiResponsesProtocol.sha256Utf8(uri.toASCIIString());
    }

    public static OpenAiResponsesEndpointPolicy exact() {
        return EXACT;
    }

    public URI uri() {
        return uri;
    }

    public String scheme() {
        return SCHEME;
    }

    public String host() {
        return HOST;
    }

    public int port() {
        return PORT;
    }

    public String path() {
        return PATH;
    }

    public String endpointHash() {
        return endpointHash;
    }

    public void requireExactUri(URI candidate) {
        requireExact(candidate);
    }

    @Override
    public String toString() {
        return "OpenAiResponsesEndpointPolicy[endpointHash=" + endpointHash + "]";
    }

    private static URI requireExact(URI candidate) {
        if (candidate == null
            || candidate.isOpaque()
            || candidate.getUserInfo() != null
            || candidate.getQuery() != null
            || candidate.getFragment() != null
            || candidate.getRawQuery() != null
            || candidate.getRawFragment() != null
            || candidate.getPort() != PORT
            || !SCHEME.equals(candidate.getScheme())
            || candidate.getHost() == null
            || !HOST.equals(candidate.getHost().toLowerCase(Locale.ROOT))
            || !PATH.equals(candidate.getRawPath())
            || !PATH.equals(candidate.normalize().getRawPath())
            || !EXACT_URI.toASCIIString().equals(candidate.toASCIIString())) {
            throw new OpenAiResponsesTransportException(
                OpenAiResponsesTransportException.Failure.ENDPOINT_REJECTED
            );
        }
        return candidate;
    }
}
