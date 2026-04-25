package com.ociworker.util;

import com.oracle.bmc.http.client.io.DuplicatableInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 适配 OCI signer：要求 body 为 {@link DuplicatableInputStream}（可 duplicate 的 InputStream）。
 * 这里以 byte[] 为底层，duplicate 返回新的 ByteArrayInputStream，从头开始可重复读取。
 */
public final class OciDuplicatableByteArrayInputStream extends InputStream implements DuplicatableInputStream {

    private final byte[] bytes;
    private ByteArrayInputStream delegate;

    public OciDuplicatableByteArrayInputStream(byte[] bytes) {
        this.bytes = bytes == null ? new byte[0] : bytes;
        this.delegate = new ByteArrayInputStream(this.bytes);
    }

    @Override
    public InputStream duplicate() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public int read() {
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) {
        return delegate.read(b, off, len);
    }

    @Override
    public int available() {
        return delegate.available();
    }

    @Override
    public synchronized void reset() {
        delegate = new ByteArrayInputStream(bytes);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}

