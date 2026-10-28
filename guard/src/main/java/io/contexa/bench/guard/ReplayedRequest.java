package io.contexa.bench.guard;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A request whose body, once read, is read again from the bytes already read, optionally presented as another request:
 * another method and path (the tool's own endpoint) and a bearer token in the {@code Authorization} header (the agent's
 * delegated access token).
 */
final class ReplayedRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final String method;
    private final String path;
    private final String authorization;
    private final java.util.Map<String, String> extraHeaders = new java.util.LinkedHashMap<>();

    /** The same request with its body replayed. */
    ReplayedRequest(HttpServletRequest request, byte[] body) {
        this(request, body, null, null, null);
    }

    /**
     * @param method        the method to present, or null for the request's own
     * @param path          the path to present (the request URI, without a context path or query), or null
     * @param authorization the value of the {@code Authorization} header to present, or null for the request's own
     */
    ReplayedRequest(HttpServletRequest request, byte[] body, String method, String path, String authorization) {
        super(request);
        this.body = body;
        this.method = method;
        this.path = path;
        this.authorization = authorization;
    }

    @Override
    public String getMethod() {
        return method != null ? method : super.getMethod();
    }

    @Override
    public String getRequestURI() {
        return path != null ? getContextPath() + path : super.getRequestURI();
    }

    @Override
    public StringBuffer getRequestURL() {
        if (path == null) {
            return super.getRequestURL();
        }
        StringBuffer url = new StringBuffer();
        url.append(getScheme()).append("://").append(getServerName());
        int port = getServerPort();
        if (port > 0 && !(port == 80 && "http".equals(getScheme())) && !(port == 443 && "https".equals(getScheme()))) {
            url.append(':').append(port);
        }
        return url.append(getRequestURI());
    }

    @Override
    public String getServletPath() {
        return path != null ? path : super.getServletPath();
    }

    @Override
    public String getPathInfo() {
        return path != null ? null : super.getPathInfo();
    }

    /** Presents one more header (for example the confirmation the agent names when it sends a request again). */
    ReplayedRequest withHeader(String name, String value) {
        extraHeaders.put(name.toLowerCase(java.util.Locale.ROOT), value);
        return this;
    }

    @Override
    public String getHeader(String name) {
        if (authorization != null && "Authorization".equalsIgnoreCase(name)) {
            return authorization;
        }
        String extra = extraHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
        if (extra != null) {
            return extra;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (authorization != null && "Authorization".equalsIgnoreCase(name)) {
            return Collections.enumeration(List.of(authorization));
        }
        String extra = extraHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
        if (extra != null) {
            return Collections.enumeration(List.of(extra));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        if (authorization == null && extraHeaders.isEmpty()) {
            return super.getHeaderNames();
        }
        List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
        if (authorization != null && names.stream().noneMatch("Authorization"::equalsIgnoreCase)) {
            names.add("Authorization");
        }
        for (String extra : extraHeaders.keySet()) {
            if (names.stream().noneMatch(extra::equalsIgnoreCase)) {
                names.add(extra);
            }
        }
        return Collections.enumeration(names);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("asynchronous reads are not used");
            }

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                return source.read(target, offset, length);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        return new BufferedReader(new InputStreamReader(getInputStream(),
                encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public int getContentLength() {
        return body.length;
    }
}
