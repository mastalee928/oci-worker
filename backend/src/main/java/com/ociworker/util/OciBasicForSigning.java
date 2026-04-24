package com.ociworker.util;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;

import java.io.InputStream;
import java.util.Objects;

/**
 * OCI 的 {@link com.oracle.bmc.http.signing.DefaultRequestSigner} 只接受
 * {@link BasicAuthenticationDetailsProvider}，而面板统一使用
 * {@link SimpleAuthenticationDetailsProvider}，二者无继承关系，需薄委托以通过
 * 编译与运行时检查。
 */
public final class OciBasicForSigning {

    private OciBasicForSigning() {
    }

    public static BasicAuthenticationDetailsProvider from(SimpleAuthenticationDetailsProvider simple) {
        Objects.requireNonNull(simple, "simple");
        return new BasicWrapper(simple);
    }

    private static final class BasicWrapper implements BasicAuthenticationDetailsProvider {

        private final SimpleAuthenticationDetailsProvider inner;

        BasicWrapper(SimpleAuthenticationDetailsProvider inner) {
            this.inner = inner;
        }

        @Override
        public String getKeyId() {
            return inner.getKeyId();
        }

        @Override
        public InputStream getPrivateKey() {
            return inner.getPrivateKey();
        }

        @Override
        @Deprecated
        public String getPassPhrase() {
            return inner.getPassPhrase();
        }

        @Override
        public char[] getPassphraseCharacters() {
            return inner.getPassphraseCharacters();
        }
    }
}
