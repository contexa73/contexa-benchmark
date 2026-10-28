package io.contexa.bench.guard;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A response that keeps what the chain writes (status and body) instead of sending it: the guard answers with a verdict of
 * its own, and reads the chain's answer only when no gateway decided the call (a filter of the chain refused it).
 */
final class BufferedResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int status = HttpServletResponse.SC_OK;
    private String message;
    private ServletOutputStream stream;
    private PrintWriter writer;

    BufferedResponse(HttpServletResponse response) {
        super(response);
    }

    int status() {
        return status;
    }

    byte[] body() {
        if (writer != null) {
            writer.flush();
        }
        return buffer.toByteArray();
    }

    String message() {
        return message;
    }

    @Override
    public void setStatus(int sc) {
        this.status = sc;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void sendError(int sc) {
        this.status = sc;
    }

    @Override
    public void sendError(int sc, String msg) {
        this.status = sc;
        this.message = msg;
    }

    @Override
    public void sendRedirect(String location) {
        this.status = HttpServletResponse.SC_FOUND;
        this.message = "redirect to " + location;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (stream == null) {
            stream = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    throw new UnsupportedOperationException("asynchronous writes are not used");
                }

                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    buffer.write(bytes, offset, length);
                }
            };
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() {
        // Nothing is sent: the answer is written once, at the end.
    }

    @Override
    public void setContentLength(int len) {
    }

    @Override
    public void setContentLengthLong(long len) {
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public void resetBuffer() {
        buffer.reset();
    }

    @Override
    public void reset() {
        buffer.reset();
        status = HttpServletResponse.SC_OK;
        message = null;
    }
}
