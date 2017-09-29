package org.apache.tomcat.websocket;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.codec.binary.Base64;
import org.apache.tomcat.util.collections.CaseInsensitiveKeyMap;
import org.apache.tomcat.util.res.StringManager;

public class WsWebSocketClient {

    private static final StringManager sm = StringManager.getManager(WsWebSocketContainer.class);
    private static final Random random = new Random();
    private static final byte[] crlf = new byte[] { 13, 10 };
    private final Log log = LogFactory.getLog(WsWebSocketContainer.class);

    private static final byte[] GET_BYTES = "GET ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] ROOT_URI_BYTES = "/".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] HTTP_VERSION_BYTES = " HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private Set<URI> redirectSet = null;

    public Session connectRecursive(Endpoint endpoint, ClientEndpointConfig clientEndpointConfiguration, URI path,
            WsWebSocketContainer websocketContainer) throws DeploymentException {

        boolean secure = false;
        ByteBuffer proxyConnect = null;
        URI proxyPath;

        // Validate scheme (and build proxyPath)
        String scheme = path.getScheme();
        if ("ws".equalsIgnoreCase(scheme)) {
            proxyPath = URI.create("http" + path.toString().substring(2));
        } else if ("wss".equalsIgnoreCase(scheme)) {
            proxyPath = URI.create("https" + path.toString().substring(3));
            secure = true;
        } else {
            throw new DeploymentException(sm.getString("wsWebSocketContainer.pathWrongScheme", scheme));
        }

        // Validate host
        String host = path.getHost();
        if (host == null) {
            throw new DeploymentException(sm.getString("wsWebSocketContainer.pathNoHost"));
        }
        int port = path.getPort();

        SocketAddress sa = null;

        // Check to see if a proxy is configured. Javadoc indicates return value
        // will never be null
        List<Proxy> proxies = ProxySelector.getDefault().select(proxyPath);
        Proxy selectedProxy = null;
        for (Proxy proxy : proxies) {
            if (proxy.type().equals(Proxy.Type.HTTP)) {
                sa = proxy.address();
                if (sa instanceof InetSocketAddress) {
                    InetSocketAddress inet = (InetSocketAddress) sa;
                    if (inet.isUnresolved()) {
                        sa = new InetSocketAddress(inet.getHostName(), inet.getPort());
                    }
                }
                selectedProxy = proxy;
                break;
            }
        }

        // If the port is not explicitly specified, compute it based on the
        // scheme
        if (port == -1) {
            if ("ws".equalsIgnoreCase(scheme)) {
                port = 80;
            } else {
                // Must be wss due to scheme validation above
                port = 443;
            }
        }

        // If sa is null, no proxy is configured so need to create sa
        if (sa == null) {
            sa = new InetSocketAddress(host, port);
        } else {
            proxyConnect = createProxyRequest(host, port);
        }

        // Create the initial HTTP request to open the WebSocket connection
        Map<String, List<String>> reqHeaders = createRequestHeaders(host, port, clientEndpointConfiguration);
        clientEndpointConfiguration.getConfigurator().beforeRequest(reqHeaders);
        if (Constants.DEFAULT_ORIGIN_HEADER_VALUE != null && !reqHeaders.containsKey(Constants.ORIGIN_HEADER_NAME)) {
            List<String> originValues = new ArrayList<>(1);
            originValues.add(Constants.DEFAULT_ORIGIN_HEADER_VALUE);
            reqHeaders.put(Constants.ORIGIN_HEADER_NAME, originValues);
        }
        ByteBuffer request = createRequest(path, reqHeaders);

        AsynchronousSocketChannel socketChannel;
        try {
            socketChannel = AsynchronousSocketChannel.open(websocketContainer.getAsynchronousChannelGroup());
        } catch (IOException ioe) {
            throw new DeploymentException(sm.getString("wsWebSocketContainer.asynchronousSocketChannelFail"), ioe);
        }

        Map<String, Object> userProperties = clientEndpointConfiguration.getUserProperties();

        // Get the connection timeout
        long timeout = Constants.IO_TIMEOUT_MS_DEFAULT;
        String timeoutValue = (String) userProperties.get(Constants.IO_TIMEOUT_MS_PROPERTY);
        if (timeoutValue != null) {
            timeout = Long.valueOf(timeoutValue).intValue();
        }

        // Set-up
        // Same size as the WsFrame input buffer
        ByteBuffer response = ByteBuffer.allocate(websocketContainer.getDefaultMaxBinaryMessageBufferSize());
        String subProtocol;
        boolean success = false;
        List<Extension> extensionsAgreed = new ArrayList<>();
        Transformation transformation = null;

        // Open the connection
        Future<Void> fConnect = socketChannel.connect(sa);
        AsyncChannelWrapper channel = null;

        if (proxyConnect != null) {
            try {
                fConnect.get(timeout, TimeUnit.MILLISECONDS);
                // Proxy CONNECT is clear text
                channel = new AsyncChannelWrapperNonSecure(socketChannel);
                writeRequest(channel, proxyConnect, timeout);
                HttpResponse httpResponse = processResponse(response, channel, timeout);
                if (httpResponse.getStatus() != 200) {
                    throw new DeploymentException(sm.getString("wsWebSocketContainer.proxyConnectFail", selectedProxy,
                            Integer.toString(httpResponse.getStatus())));
                }
            } catch (TimeoutException | InterruptedException | ExecutionException | EOFException e) {
                if (channel != null) {
                    channel.close();
                }
                throw new DeploymentException(sm.getString("wsWebSocketContainer.httpRequestFailed"), e);
            }
        }

        if (secure) {
            // Regardless of whether a non-secure wrapper was created for a
            // proxy CONNECT, need to use TLS from this point on so wrap the
            // original AsynchronousSocketChannel
            SSLEngine sslEngine = createSSLEngine(userProperties);
            channel = new AsyncChannelWrapperSecure(socketChannel, sslEngine);
        } else if (channel == null) {
            // Only need to wrap as this point if it wasn't wrapped to process a
            // proxy CONNECT
            channel = new AsyncChannelWrapperNonSecure(socketChannel);
        }

        try {
            fConnect.get(timeout, TimeUnit.MILLISECONDS);

            Future<Void> fHandshake = channel.handshake();
            fHandshake.get(timeout, TimeUnit.MILLISECONDS);

            writeRequest(channel, request, timeout);

            HttpResponse httpResponse = processResponse(response, channel, timeout);

            // Check maximum permitted redirects
            int maxRedirects = Constants.MAX_REDIRECTIONS_DEFAULT;
            String maxRedirectsValue = (String) userProperties.get(Constants.MAX_REDIRECTIONS_PROPERTY);
            if (maxRedirectsValue != null) {
                maxRedirects = Integer.parseInt(maxRedirectsValue);
            }

            if (httpResponse.status != 101) {
                if (isRedirectStatus(httpResponse.status)) {
                    List<String> locationHeader = httpResponse.getHandshakeResponse().getHeaders()
                            .get(Constants.LOCATION_HEADER_NAME);

                    if (locationHeader == null || locationHeader.isEmpty() || locationHeader.get(0) == null
                            || locationHeader.get(0).isEmpty()) {
                        throw new DeploymentException(sm.getString("wsWebSocketContainer.missingLocationHeader",
                                Integer.toString(httpResponse.status)));
                    }

                    URI redirectLocation = URI.create(locationHeader.get(0)).normalize();

                    if (!redirectLocation.isAbsolute()) {
                        redirectLocation = path.resolve(redirectLocation);
                    }

                    String redirectScheme = redirectLocation.getScheme().toLowerCase();

                    if (redirectScheme.startsWith("http")) {
                        redirectLocation = new URI(redirectScheme.replace("http", "ws"), redirectLocation.getUserInfo(),
                                redirectLocation.getHost(), redirectLocation.getPort(), redirectLocation.getPath(),
                                redirectLocation.getQuery(), redirectLocation.getFragment());
                    }

                    if (redirectSet == null) {
                        redirectSet = new HashSet<>(maxRedirects);
                    }

                    if (!redirectSet.add(redirectLocation) || redirectSet.size() > maxRedirects) {
                        throw new DeploymentException(
                                sm.getString("wsWebSocketContainer.redirectThreshold", redirectLocation,
                                        Integer.toString(redirectSet.size()), Integer.toString(maxRedirects)));
                    }

                    return connectRecursive(endpoint, clientEndpointConfiguration, redirectLocation,
                            websocketContainer);

                }

                else if (httpResponse.status == 401) {

                    if (userProperties.get(Constants.AUTHORIZATION_HEADER_NAME) != null) {
                        throw new DeploymentException(
                                sm.getString("wsWebSocketContainer.failedAuthentication", httpResponse.status));
                    }

                    List<String> wwwAuthenticateHeaders = httpResponse.getHandshakeResponse().getHeaders()
                            .get(Constants.WWW_AUTHENTICATE_HEADER_NAME);

                    if (wwwAuthenticateHeaders == null || wwwAuthenticateHeaders.isEmpty()
                            || wwwAuthenticateHeaders.get(0) == null || wwwAuthenticateHeaders.get(0).isEmpty()) {
                        throw new DeploymentException(sm.getString("wsWebSocketContainer.missingWWWAuthenticateHeader",
                                Integer.toString(httpResponse.status)));
                    }

                    String authScheme = wwwAuthenticateHeaders.get(0).split("\\s+", 2)[0];
                    String requestUri = new String(request.array(), StandardCharsets.ISO_8859_1).split("\\s", 3)[1];

                    Authenticator auth = AuthenticatorFactory.getAuthenticator(authScheme);

                    if (auth == null) {
                        throw new DeploymentException(sm.getString("wsWebSocketContainer.unsupportedAuthScheme",
                                httpResponse.status, authScheme));
                    }

                    userProperties.put(Constants.AUTHORIZATION_HEADER_NAME,
                            auth.getAuthorization(requestUri, wwwAuthenticateHeaders.get(0), userProperties));

                    return connectRecursive(endpoint, clientEndpointConfiguration, path, websocketContainer);

                }

                else {
                    throw new DeploymentException(
                            sm.getString("wsWebSocketContainer.invalidStatus", Integer.toString(httpResponse.status)));
                }
            }
            HandshakeResponse handshakeResponse = httpResponse.getHandshakeResponse();
            clientEndpointConfiguration.getConfigurator().afterResponse(handshakeResponse);

            // Sub-protocol
            List<String> protocolHeaders = handshakeResponse.getHeaders().get(Constants.WS_PROTOCOL_HEADER_NAME);
            if (protocolHeaders == null || protocolHeaders.size() == 0) {
                subProtocol = null;
            } else if (protocolHeaders.size() == 1) {
                subProtocol = protocolHeaders.get(0);
            } else {
                throw new DeploymentException(sm.getString("wsWebSocketContainer.invalidSubProtocol"));
            }

            // Extensions
            // Should normally only be one header but handle the case of
            // multiple headers
            List<String> extHeaders = handshakeResponse.getHeaders().get(Constants.WS_EXTENSIONS_HEADER_NAME);
            if (extHeaders != null) {
                for (String extHeader : extHeaders) {
                    Util.parseExtensionHeader(extensionsAgreed, extHeader);
                }
            }

            // Build the transformations
            TransformationFactory factory = TransformationFactory.getInstance();
            for (Extension extension : extensionsAgreed) {
                List<List<Extension.Parameter>> wrapper = new ArrayList<>(1);
                wrapper.add(extension.getParameters());
                Transformation t = factory.create(extension.getName(), wrapper, false);
                if (t == null) {
                    throw new DeploymentException(sm.getString("wsWebSocketContainer.invalidExtensionParameters"));
                }
                if (transformation == null) {
                    transformation = t;
                } else {
                    transformation.setNext(t);
                }
            }

            success = true;
        } catch (ExecutionException | InterruptedException | SSLException | EOFException | TimeoutException
                | URISyntaxException | AuthenticationException e) {
            throw new DeploymentException(sm.getString("wsWebSocketContainer.httpRequestFailed"), e);
        } finally {
            if (!success) {
                channel.close();
            }

            if (redirectSet != null && !redirectSet.isEmpty()) {
                redirectSet.clear();
            }
        }

        // Switch to WebSocket
        WsRemoteEndpointImplClient wsRemoteEndpointClient = new WsRemoteEndpointImplClient(channel);

        WsSession wsSession = new WsSession(endpoint, wsRemoteEndpointClient, websocketContainer, null, null, null,
                null, null, extensionsAgreed, subProtocol, Collections.<String, String>emptyMap(), secure,
                clientEndpointConfiguration);

        WsFrameClient wsFrameClient = new WsFrameClient(response, channel, wsSession, transformation);
        // WsFrame adds the necessary final transformations. Copy the
        // completed transformation chain to the remote end point.
        wsRemoteEndpointClient.setTransformation(wsFrameClient.getTransformation());

        endpoint.onOpen(wsSession, clientEndpointConfiguration);
        websocketContainer.registerSession(endpoint, wsSession);

        /*
         * It is possible that the server sent one or more messages as soon as the
         * WebSocket connection was established. Depending on the exact timing of when
         * those messages were sent they could be sat in the input buffer waiting to be
         * read and will not trigger a "data available to read" event. Therefore, it is
         * necessary to process the input buffer here. Note that this happens on the
         * current thread which means that this thread will be used for any onMessage
         * notifications. This is a special case. Subsequent "data available to read"
         * events will be handled by threads from the AsyncChannelGroup's executor.
         */
        wsFrameClient.startInputProcessing();

        return wsSession;
    }

    private static void writeRequest(AsyncChannelWrapper channel, ByteBuffer request, long timeout)
            throws TimeoutException, InterruptedException, ExecutionException {
        int toWrite = request.limit();

        Future<Integer> fWrite = channel.write(request);
        Integer thisWrite = fWrite.get(timeout, TimeUnit.MILLISECONDS);
        toWrite -= thisWrite.intValue();

        while (toWrite > 0) {
            fWrite = channel.write(request);
            thisWrite = fWrite.get(timeout, TimeUnit.MILLISECONDS);
            toWrite -= thisWrite.intValue();
        }
    }

    private static boolean isRedirectStatus(int httpResponseCode) {

        boolean isRedirect = false;

        switch (httpResponseCode) {
        case Constants.MULTIPLE_CHOICES:
        case Constants.MOVED_PERMANENTLY:
        case Constants.FOUND:
        case Constants.SEE_OTHER:
        case Constants.USE_PROXY:
        case Constants.TEMPORARY_REDIRECT:
            isRedirect = true;
            break;
        default:
            break;
        }

        return isRedirect;
    }

    private static ByteBuffer createProxyRequest(String host, int port) {
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ");
        request.append(host);
        request.append(':');
        request.append(port);

        request.append(" HTTP/1.1\r\nProxy-Connection: keep-alive\r\nConnection: keepalive\r\nHost: ");
        request.append(host);
        request.append(':');
        request.append(port);

        request.append("\r\n\r\n");

        byte[] bytes = request.toString().getBytes(StandardCharsets.ISO_8859_1);
        return ByteBuffer.wrap(bytes);
    }

    private static Map<String, List<String>> createRequestHeaders(String host, int port,
            ClientEndpointConfig clientEndpointConfiguration) {

        Map<String, List<String>> headers = new HashMap<>();
        List<Extension> extensions = clientEndpointConfiguration.getExtensions();
        List<String> subProtocols = clientEndpointConfiguration.getPreferredSubprotocols();
        Map<String, Object> userProperties = clientEndpointConfiguration.getUserProperties();

        if (userProperties.get(Constants.AUTHORIZATION_HEADER_NAME) != null) {
            List<String> authValues = new ArrayList<>(1);
            authValues.add((String) userProperties.get(Constants.AUTHORIZATION_HEADER_NAME));
            headers.put(Constants.AUTHORIZATION_HEADER_NAME, authValues);
        }

        // Host header
        List<String> hostValues = new ArrayList<>(1);
        if (port == -1) {
            hostValues.add(host);
        } else {
            hostValues.add(host + ':' + port);
        }

        headers.put(Constants.HOST_HEADER_NAME, hostValues);

        // Upgrade header
        List<String> upgradeValues = new ArrayList<>(1);
        upgradeValues.add(Constants.UPGRADE_HEADER_VALUE);
        headers.put(Constants.UPGRADE_HEADER_NAME, upgradeValues);

        // Connection header
        List<String> connectionValues = new ArrayList<>(1);
        connectionValues.add(Constants.CONNECTION_HEADER_VALUE);
        headers.put(Constants.CONNECTION_HEADER_NAME, connectionValues);

        // WebSocket version header
        List<String> wsVersionValues = new ArrayList<>(1);
        wsVersionValues.add(Constants.WS_VERSION_HEADER_VALUE);
        headers.put(Constants.WS_VERSION_HEADER_NAME, wsVersionValues);

        // WebSocket key
        List<String> wsKeyValues = new ArrayList<>(1);
        wsKeyValues.add(generateWsKeyValue());
        headers.put(Constants.WS_KEY_HEADER_NAME, wsKeyValues);

        // WebSocket sub-protocols
        if (subProtocols != null && subProtocols.size() > 0) {
            headers.put(Constants.WS_PROTOCOL_HEADER_NAME, subProtocols);
        }

        // WebSocket extensions
        if (extensions != null && extensions.size() > 0) {
            headers.put(Constants.WS_EXTENSIONS_HEADER_NAME, generateExtensionHeaders(extensions));
        }

        return headers;
    }

    private static List<String> generateExtensionHeaders(List<Extension> extensions) {
        List<String> result = new ArrayList<>(extensions.size());
        for (Extension extension : extensions) {
            StringBuilder header = new StringBuilder();
            header.append(extension.getName());
            for (Extension.Parameter param : extension.getParameters()) {
                header.append(';');
                header.append(param.getName());
                String value = param.getValue();
                if (value != null && value.length() > 0) {
                    header.append('=');
                    header.append(value);
                }
            }
            result.add(header.toString());
        }
        return result;
    }

    private static String generateWsKeyValue() {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        return Base64.encodeBase64String(keyBytes);
    }

    private static ByteBuffer createRequest(URI uri, Map<String, List<String>> reqHeaders) {
        ByteBuffer result = ByteBuffer.allocate(4 * 1024);

        // Request line
        result.put(GET_BYTES);
        if (null == uri.getPath() || "".equals(uri.getPath())) {
            result.put(ROOT_URI_BYTES);
        } else {
            result.put(uri.getRawPath().getBytes(StandardCharsets.ISO_8859_1));
        }
        String query = uri.getRawQuery();
        if (query != null) {
            result.put((byte) '?');
            result.put(query.getBytes(StandardCharsets.ISO_8859_1));
        }
        result.put(HTTP_VERSION_BYTES);

        // Headers
        for (Entry<String, List<String>> entry : reqHeaders.entrySet()) {
            addHeader(result, entry.getKey(), entry.getValue());
        }

        // Terminating CRLF
        result.put(crlf);

        result.flip();

        return result;
    }

    private static void addHeader(ByteBuffer result, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }

        result.put(key.getBytes(StandardCharsets.ISO_8859_1));
        result.put(": ".getBytes(StandardCharsets.ISO_8859_1));
        result.put(StringUtils.join(values).getBytes(StandardCharsets.ISO_8859_1));
        result.put(crlf);
    }

    /**
     * Process response, blocking until HTTP response has been fully received.
     * 
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws DeploymentException
     * @throws TimeoutException
     */
    private HttpResponse processResponse(ByteBuffer response, AsyncChannelWrapper channel, long timeout)
            throws InterruptedException, ExecutionException, DeploymentException, EOFException, TimeoutException {

        Map<String, List<String>> headers = new CaseInsensitiveKeyMap<>();

        int status = 0;
        boolean readStatus = false;
        boolean readHeaders = false;
        String line = null;
        while (!readHeaders) {
            // On entering loop buffer will be empty and at the start of a new
            // loop the buffer will have been fully read.
            response.clear();
            // Blocking read
            Future<Integer> read = channel.read(response);
            Integer bytesRead = read.get(timeout, TimeUnit.MILLISECONDS);
            if (bytesRead.intValue() == -1) {
                throw new EOFException();
            }
            response.flip();
            while (response.hasRemaining() && !readHeaders) {
                if (line == null) {
                    line = readLine(response);
                } else {
                    line += readLine(response);
                }
                if ("\r\n".equals(line)) {
                    readHeaders = true;
                } else if (line.endsWith("\r\n")) {
                    if (readStatus) {
                        parseHeaders(line, headers);
                    } else {
                        status = parseStatus(line);
                        readStatus = true;
                    }
                    line = null;
                }
            }
        }

        return new HttpResponse(status, new WsHandshakeResponse(headers));
    }

    private int parseStatus(String line) throws DeploymentException {
        // This client only understands HTTP 1.
        // RFC2616 is case specific
        String[] parts = line.trim().split(" ");
        // CONNECT for proxy may return a 1.0 response
        if (parts.length < 2 || !("HTTP/1.0".equals(parts[0]) || "HTTP/1.1".equals(parts[0]))) {
            throw new DeploymentException(sm.getString("wsWebSocketContainer.invalidStatus", line));
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException nfe) {
            throw new DeploymentException(sm.getString("wsWebSocketContainer.invalidStatus", line));
        }
    }

    private void parseHeaders(String line, Map<String, List<String>> headers) {
        // Treat headers as single values by default.

        int index = line.indexOf(':');
        if (index == -1) {
            log.warn(sm.getString("wsWebSocketContainer.invalidHeader", line));
            return;
        }
        // Header names are case insensitive so always use lower case
        String headerName = line.substring(0, index).trim().toLowerCase(Locale.ENGLISH);
        // Multi-value headers are stored as a single header and the client is
        // expected to handle splitting into individual values
        String headerValue = line.substring(index + 1).trim();

        List<String> values = headers.get(headerName);
        if (values == null) {
            values = new ArrayList<>(1);
            headers.put(headerName, values);
        }
        values.add(headerValue);
    }

    private String readLine(ByteBuffer response) {
        // All ISO-8859-1
        StringBuilder sb = new StringBuilder();

        char c = 0;
        while (response.hasRemaining()) {
            c = (char) response.get();
            sb.append(c);
            if (c == 10) {
                break;
            }
        }

        return sb.toString();
    }

    private SSLEngine createSSLEngine(Map<String, Object> userProperties) throws DeploymentException {

        try {
            // See if a custom SSLContext has been provided
            SSLContext sslContext = (SSLContext) userProperties.get(Constants.SSL_CONTEXT_PROPERTY);

            if (sslContext == null) {
                // Create the SSL Context
                sslContext = SSLContext.getInstance("TLS");

                // Trust store
                String sslTrustStoreValue = (String) userProperties.get(Constants.SSL_TRUSTSTORE_PROPERTY);
                if (sslTrustStoreValue != null) {
                    String sslTrustStorePwdValue = (String) userProperties.get(Constants.SSL_TRUSTSTORE_PWD_PROPERTY);
                    if (sslTrustStorePwdValue == null) {
                        sslTrustStorePwdValue = Constants.SSL_TRUSTSTORE_PWD_DEFAULT;
                    }

                    File keyStoreFile = new File(sslTrustStoreValue);
                    KeyStore ks = KeyStore.getInstance("JKS");
                    try (InputStream is = new FileInputStream(keyStoreFile)) {
                        ks.load(is, sslTrustStorePwdValue.toCharArray());
                    }

                    TrustManagerFactory tmf = TrustManagerFactory
                            .getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(ks);

                    sslContext.init(null, tmf.getTrustManagers(), null);
                } else {
                    sslContext.init(null, null, null);
                }
            }

            SSLEngine engine = sslContext.createSSLEngine();

            String sslProtocolsValue = (String) userProperties.get(Constants.SSL_PROTOCOLS_PROPERTY);
            if (sslProtocolsValue != null) {
                engine.setEnabledProtocols(sslProtocolsValue.split(","));
            }

            engine.setUseClientMode(true);

            return engine;
        } catch (Exception e) {
            throw new DeploymentException(sm.getString("wsWebSocketContainer.sslEngineFail"), e);
        }
    }

    private static class HttpResponse {
        private final int status;
        private final HandshakeResponse handshakeResponse;

        public HttpResponse(int status, HandshakeResponse handshakeResponse) {
            this.status = status;
            this.handshakeResponse = handshakeResponse;
        }

        public int getStatus() {
            return status;
        }

        public HandshakeResponse getHandshakeResponse() {
            return handshakeResponse;
        }
    }

}
