package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.Deadline;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.ExchangeResult;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.Resolution;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.SecureChannel;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.SecureNetwork;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Default-trust JDK DNS/TLS channel bound to one admitted public address. */
final class OpenAiResponsesJdkSecureNetwork implements SecureNetwork {

    private final Clock clock;
    private final SSLSocketFactory sslSocketFactory;

    OpenAiResponsesJdkSecureNetwork(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        try {
            this.sslSocketFactory = SSLContext.getDefault().getSocketFactory();
        } catch (GeneralSecurityException failure) {
            throw failure(OpenAiResponsesTransportException.Failure.TLS_FAILURE);
        }
    }

    @Override
    public Resolution resolve(
        OpenAiResponsesEndpointPolicy endpoint,
        OpenAiResponsesTransportPort.Request request,
        Deadline deadline
    ) {
        OpenAiResponsesNetworkSupport.requireNotCancelled(request);
        FutureTask<InetAddress[]> task = new FutureTask<>(
            () -> InetAddress.getAllByName(endpoint.host())
        );
        Thread.ofVirtual().name("openai-dns-resolution").start(task);
        try {
            InetAddress[] resolved = task.get(
                deadline.remaining().toNanos(),
                TimeUnit.NANOSECONDS
            );
            if (resolved.length == 0) {
                throw failure(OpenAiResponsesTransportException.Failure.DNS_EMPTY);
            }
            return new Resolution(
                endpoint.endpointHash(),
                Arrays.asList(resolved),
                clock.instant()
            );
        } catch (TimeoutException failure) {
            task.cancel(true);
            throw failure(OpenAiResponsesTransportException.Failure.TIMEOUT);
        } catch (InterruptedException failure) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(OpenAiResponsesTransportException.Failure.CANCELLED);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof UnknownHostException) {
                throw failure(OpenAiResponsesTransportException.Failure.DNS_FAILURE);
            }
            throw failure(OpenAiResponsesTransportException.Failure.DNS_FAILURE);
        }
    }

    @Override
    public SecureChannel connect(
        OpenAiResponsesEndpointPolicy endpoint,
        Resolution resolution,
        OpenAiResponsesTransportPort.Request request,
        Deadline deadline
    ) {
        OpenAiResponsesNetworkSupport.requireNotCancelled(request);
        InetAddress selected = resolution.selectedAddress();
        Socket plain = new Socket();
        try {
            int connectMillis = (int) Math.min(
                request.connectTimeout().toMillis(),
                deadline.remainingMillis()
            );
            plain.connect(
                new InetSocketAddress(selected, endpoint.port()),
                Math.max(1, connectMillis)
            );
            if (!selected.equals(plain.getInetAddress())) {
                closeQuietly(plain);
                throw failure(OpenAiResponsesTransportException.Failure.CONNECTION_DRIFT);
            }
            plain.setSoTimeout(deadline.remainingMillis());
            SSLSocket tls = (SSLSocket) sslSocketFactory.createSocket(
                plain,
                endpoint.host(),
                endpoint.port(),
                true
            );
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(endpoint.host())));
            parameters.setProtocols(enabledTlsProtocols(tls.getSupportedProtocols()));
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(deadline.remainingMillis());
            tls.startHandshake();
            OpenAiResponsesNetworkSupport.requireNotCancelled(request);
            SSLSession session = tls.getSession();
            String tlsPeerHash = tlsPeerHash(endpoint, session, clock.instant());
            return new JdkSecureChannel(
                tls,
                endpoint,
                resolution,
                OpenAiResponsesNetworkSupport.addressHash(selected),
                tlsPeerHash
            );
        } catch (OpenAiResponsesTransportException failure) {
            closeQuietly(plain);
            throw failure;
        } catch (SocketTimeoutException failure) {
            closeQuietly(plain);
            throw failure(OpenAiResponsesTransportException.Failure.TIMEOUT);
        } catch (SSLPeerUnverifiedException failure) {
            closeQuietly(plain);
            throw failure(
                OpenAiResponsesTransportException.Failure.TLS_HOSTNAME_MISMATCH
            );
        } catch (IOException failure) {
            closeQuietly(plain);
            throw failure(OpenAiResponsesTransportException.Failure.TLS_FAILURE);
        }
    }

    private static String[] enabledTlsProtocols(String[] supported) {
        Set<String> allowed = Set.of("TLSv1.3", "TLSv1.2");
        String[] output = Arrays.stream(supported)
            .filter(allowed::contains)
            .toArray(String[]::new);
        if (output.length == 0) {
            throw failure(OpenAiResponsesTransportException.Failure.TLS_FAILURE);
        }
        return output;
    }

    private static String tlsPeerHash(
        OpenAiResponsesEndpointPolicy endpoint,
        SSLSession session,
        Instant now
    ) throws SSLPeerUnverifiedException {
        if (!endpoint.host().equalsIgnoreCase(session.getPeerHost())) {
            throw failure(
                OpenAiResponsesTransportException.Failure.TLS_HOSTNAME_MISMATCH
            );
        }
        Certificate[] certificates = session.getPeerCertificates();
        if (certificates.length == 0
            || !(certificates[0] instanceof X509Certificate leaf)) {
            throw failure(OpenAiResponsesTransportException.Failure.TLS_CHAIN_INVALID);
        }
        try {
            leaf.checkValidity(java.util.Date.from(now));
        } catch (CertificateExpiredException failure) {
            throw failure(
                OpenAiResponsesTransportException.Failure.TLS_CERTIFICATE_EXPIRED
            );
        } catch (CertificateNotYetValidException failure) {
            throw failure(OpenAiResponsesTransportException.Failure.TLS_CHAIN_INVALID);
        }
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        try {
            canonical.write(endpoint.endpointHash().getBytes(StandardCharsets.US_ASCII));
            canonical.write(session.getProtocol().getBytes(StandardCharsets.US_ASCII));
            canonical.write(session.getCipherSuite().getBytes(StandardCharsets.US_ASCII));
            canonical.write(leaf.getPublicKey().getEncoded());
            for (Certificate certificate : certificates) {
                canonical.write(certificate.getEncoded());
            }
        } catch (IOException | CertificateEncodingException failure) {
            throw failure(OpenAiResponsesTransportException.Failure.TLS_CHAIN_INVALID);
        }
        return OpenAiResponsesProtocol.sha256(canonical.toByteArray());
    }

    private static final class JdkSecureChannel implements SecureChannel {
        private final SSLSocket socket;
        private final OpenAiResponsesEndpointPolicy endpoint;
        private final Resolution resolution;
        private final String connectedAddressHash;
        private final String tlsPeerHash;
        private final AtomicBoolean used = new AtomicBoolean();

        private JdkSecureChannel(
            SSLSocket socket,
            OpenAiResponsesEndpointPolicy endpoint,
            Resolution resolution,
            String connectedAddressHash,
            String tlsPeerHash
        ) {
            this.socket = socket;
            this.endpoint = endpoint;
            this.resolution = resolution;
            this.connectedAddressHash = connectedAddressHash;
            this.tlsPeerHash = tlsPeerHash;
        }

        @Override
        public ExchangeResult exchange(
            OpenAiResponsesTransportPort.Request request,
            byte[] secret,
            String clientRequestId,
            Deadline deadline
        ) {
            if (!used.compareAndSet(false, true)) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
            OpenAiResponsesHttpCodec.requireApiKey(secret);
            OpenAiResponsesNetworkSupport.requireNotCancelled(request);
            try {
                socket.setSoTimeout(deadline.remainingMillis());
                OpenAiResponsesHttpCodec.writeRequest(
                    socket.getOutputStream(),
                    endpoint,
                    request,
                    secret,
                    clientRequestId
                );
                OpenAiResponsesNetworkSupport.requireNotCancelled(request);
                return OpenAiResponsesHttpCodec.readResponse(
                    socket.getInputStream(),
                    request,
                    clientRequestId,
                    deadline
                );
            } catch (SocketTimeoutException failure) {
                throw failure(OpenAiResponsesTransportException.Failure.TIMEOUT);
            } catch (IOException failure) {
                throw failure(OpenAiResponsesTransportException.Failure.IO_FAILURE);
            }
        }

        @Override
        public String connectedAddressHash() {
            return connectedAddressHash;
        }

        @Override
        public String tlsPeerHash() {
            return tlsPeerHash;
        }

        @Override
        public boolean tlsVerified() {
            return resolution.addressHashes().contains(connectedAddressHash)
                && socket.isConnected();
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The transport is already closing and no retry or fallback is permitted.
        }
    }

    private static OpenAiResponsesTransportException failure(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return new OpenAiResponsesTransportException(failure);
    }
}
