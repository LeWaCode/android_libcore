/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.harmony.xnet.provider.jsse;

import java.io.IOException;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLPermission;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionBindingEvent;
import javax.net.ssl.SSLSessionBindingListener;
import javax.net.ssl.SSLSessionContext;
import javax.security.cert.CertificateEncodingException;
import org.apache.harmony.luni.util.TwoKeyHashMap;
import org.apache.harmony.security.provider.cert.X509CertImpl;

/**
 * Implementation of the class OpenSSLSessionImpl
 * based on OpenSSL.
 */
public class OpenSSLSessionImpl implements SSLSession {

    private long creationTime = 0;
    long lastAccessedTime = 0;
    X509Certificate[] localCertificates;
    X509Certificate[] peerCertificates;

    private boolean isValid = true;
    private TwoKeyHashMap values = new TwoKeyHashMap();
    private javax.security.cert.X509Certificate[] peerCertificateChain;
    protected int sslSessionNativePointer;
    private String peerHost;
    private int peerPort = -1;
    private String cipherSuite;
    private String protocol;
    private AbstractSessionContext sessionContext;
    private byte[] id;

    /**
     * Class constructor creates an SSL session context given the appropriate
     * SSL parameters.
     *
     * @param session the Identifier for SSL session
     * @param sslParameters the SSL parameters like ciphers' suites etc.
     */
    protected OpenSSLSessionImpl(int sslSessionNativePointer, X509Certificate[] localCertificates,
            String peerHost, int peerPort, AbstractSessionContext sessionContext) {
        this.sslSessionNativePointer = sslSessionNativePointer;
        this.localCertificates = localCertificates;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.sessionContext = sessionContext;
    }

    /**
     * Constructs a session from a byte[] containing DER data. This
     * allows loading the saved session.
     * @throws IOException
     */
    OpenSSLSessionImpl(byte[] derData,
            String peerHost, int peerPort,
            javax.security.cert.X509Certificate[] peerCertificateChain,
            AbstractSessionContext sessionContext)
            throws IOException {
        this(NativeCrypto.d2i_SSL_SESSION(derData, derData.length),
             null,
             peerHost,
             peerPort,
             sessionContext);
        this.peerCertificateChain = peerCertificateChain;
        // TODO move this check into native code so we can throw an error with more information
        if (this.sslSessionNativePointer == 0) {
            throw new IOException("Invalid session data");
        }
    }

    /**
     * Gets the identifier of the actual SSL session
     * @return array of sessions' identifiers.
     */
    public byte[] getId() {
        if (id == null) {
            resetId();
        }
        return id;
    }

    /**
     * Reset the id field to the current value found in the native
     * SSL_SESSION. It can change during the lifetime of the session
     * because while a session is created during initial handshake,
     * with handshake_cutthrough, the SSL_do_handshake may return
     * before we have read the session ticket from the server side and
     * therefore have computed no id based on the SHA of the ticket.
     */
    void resetId() {
        id = NativeCrypto.SSL_SESSION_session_id(sslSessionNativePointer);
    }

    /**
     * Get the session object in DER format. This allows saving the session
     * data or sharing it with other processes.
     */
    byte[] getEncoded() {
        return NativeCrypto.i2d_SSL_SESSION(sslSessionNativePointer);
    }

    /**
     * Gets the creation time of the SSL session.
     * @return the session's creation time in milliseconds since the epoch
     */
    public long getCreationTime() {
        if (creationTime == 0) {
            creationTime = NativeCrypto.SSL_SESSION_get_time(sslSessionNativePointer);
        }
        return creationTime;
    }

    /**
     * Gives the last time this concrete SSL session was accessed. Accessing
     * here is to mean that a new connection with the same SSL context data was
     * established.
     *
     * @return the session's last access time in milliseconds since the epoch
     */
    public long getLastAccessedTime() {
        return (lastAccessedTime == 0) ? getCreationTime() : lastAccessedTime;
    }

    /**
     * Gives the largest buffer size for the application's data bound to this
     * concrete SSL session.
     * @return the largest buffer size
     */
    public int getApplicationBufferSize() {
        return SSLRecordProtocol.MAX_DATA_LENGTH;
    }

    /**
     * Gives the largest SSL/TLS packet size one can expect for this concrete
     * SSL session.
     * @return the largest packet size
     */
    public int getPacketBufferSize() {
        return SSLRecordProtocol.MAX_SSL_PACKET_SIZE;
    }

    /**
     * Gives the principal (subject) of this concrete SSL session used in the
     * handshaking phase of the connection.
     * @return a X509 certificate or null if no principal was defined
     */
    public Principal getLocalPrincipal() {
        if (localCertificates != null && localCertificates.length > 0) {
            return localCertificates[0].getSubjectX500Principal();
        } else {
            return null;
        }
    }

    /**
     * Gives the certificate(s) of the principal (subject) of this concrete SSL
     * session used in the handshaking phase of the connection. The OpenSSL
     * native method supports only RSA certificates.
     * @return an array of certificates (the local one first and then eventually
     *         that of the certification authority) or null if no certificate
     *         were used during the handshaking phase.
     */
    public Certificate[] getLocalCertificates() {
        return localCertificates;
    }

    /**
     * Gives the certificate(s) of the peer in this SSL session
     * used in the handshaking phase of the connection.
     * Please notice hat this method is superseded by
     * <code>getPeerCertificates()</code>.
     * @return an array of X509 certificates (the peer's one first and then
     *         eventually that of the certification authority) or null if no
     *         certificate were used during the SSL connection.
     * @throws <code>SSLPeerUnverifiedCertificateException</code> if either a
     *         not X509 certificate was used (i.e. Kerberos certificates) or the
     *         peer could not be verified.
     */
    public javax.security.cert.X509Certificate[] getPeerCertificateChain()
            throws SSLPeerUnverifiedException {
        if (peerCertificateChain == null) {
            try {
                byte[][] bytes
                        = NativeCrypto.SSL_SESSION_get_peer_cert_chain(
                                sessionContext.sslCtxNativePointer, sslSessionNativePointer);
                if (bytes == null) {
                    throw new SSLPeerUnverifiedException("No certificate available");
                }

                peerCertificateChain = new javax.security.cert.X509Certificate[bytes.length];

                for(int i = 0; i < bytes.length; i++) {
                    peerCertificateChain[i]
                        = javax.security.cert.X509Certificate.getInstance(bytes[i]);
                }

                return peerCertificateChain;
            } catch (javax.security.cert.CertificateException e) {
                throw new SSLPeerUnverifiedException(e.getMessage());
            }
        } else {
            return peerCertificateChain;
        }
    }

    /**
     * Gives the identitity of the peer in this SSL session
     * determined via certificate(s).
     * @return an array of X509 certificates (the peer's one first and then
     *         eventually that of the certification authority) or null if no
     *         certificate were used during the SSL connection.
     * @throws <code>SSLPeerUnverifiedException</code> if either a not X509
     *         certificate was used (i.e. Kerberos certificates) or the peer
     *         could not be verified.
     */
    public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
        if (peerCertificates == null) {
            if (peerCertificateChain == null) getPeerCertificateChain();
            try {
                if (peerCertificateChain.length == 0) return new X509Certificate[]{};

                peerCertificates = new X509CertImpl[peerCertificateChain.length];
                for(int i = 0; i < peerCertificates.length; i++) {
                    peerCertificates[i] = new X509CertImpl(peerCertificateChain[i].getEncoded());
                }
                return peerCertificates;
            } catch (SSLPeerUnverifiedException e) {
                return new X509Certificate[]{};
            } catch (IOException e) {
                return new X509Certificate[]{};
            } catch (CertificateEncodingException e) {
                return new X509Certificate[]{};
            }
        } else {
            return peerCertificates;
        }
    }

    /**
     * The identity of the principal that was used by the peer during the SSL
     * handshake phase is returned by this method.
     * @return a X500Principal of the last certificate for X509-based
     *         cipher suites. If no principal was sent, then null is returned.
     * @throws <code>SSLPeerUnverifiedException</code> if either a not X509
     *         certificate was used (i.e. Kerberos certificates) or the
     *         peer does not exist.
     *
     */
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        getPeerCertificates();
        if (peerCertificates == null) {
            throw new SSLPeerUnverifiedException("No peer certificate");
        }
        return peerCertificates[0].getSubjectX500Principal();
    }

    /**
     * The peer's host name used in this SSL session is returned. It is the host
     * name of the client for the server; and that of the server for the client.
     * It is not a reliable way to get a fully qualified host name: it is mainly
     * used internally to implement links for a temporary cache of SSL sessions.
     *
     * @return the host name of the peer, or null if no information is
     *         available.
     *
     */
    public String getPeerHost() {
        return peerHost;
    }

    /**
     * Gives the peer's port number for the actual SSL session. It is the port
     * number of the client for the server; and that of the server for the
     * client. It is not a reliable way to get a peer's port number: it is
     * mainly used internally to implement links for a temporary cache of SSL
     * sessions.
     * @return the peer's port number, or -1 if no one is available.
     *
     */
    public int getPeerPort() {
        return peerPort;
    }

    /**
     * Gives back a string identifier of the crypto tools used in the actual SSL
     * session. For example AES_256_WITH_MD5.
     *
     * @return an identifier for all the cryptographic algorithms used in the
     *         actual SSL session.
     */
    public String getCipherSuite() {
        if (cipherSuite == null) {
            String name = NativeCrypto.SSL_SESSION_cipher(sslSessionNativePointer);
            cipherSuite = NativeCrypto.OPENSSL_TO_STANDARD.get(name);
            if (cipherSuite == null) {
                cipherSuite = name;
            }
        }
        return cipherSuite;
    }

    /**
     * Gives back the standard version name of the SSL protocol used in all
     * connections pertaining to this SSL session.
     *
     * @return the standard version name of the SSL protocol used in all
     *         connections pertaining to this SSL session.
     *
     */
    public String getProtocol() {
        if (protocol == null) {
            protocol = NativeCrypto.SSL_SESSION_get_version(sslSessionNativePointer);
        }
        return protocol;
    }

    /**
     * Gives back the context to which the actual SSL session is bound. A SSL
     * context consists of (1) a possible delegate, (2) a provider and (3) a
     * protocol. If the security manager is activated and one tries to access
     * the SSL context an exception may be thrown if a
     * <code>SSLPermission("getSSLSessionContext")</code>
     * permission is not set.
     * @return the SSL context used for this session, or null if it is
     * unavailable.
     */
    public SSLSessionContext getSessionContext() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SSLPermission("getSSLSessionContext"));
        }
        return sessionContext;
    }

    /**
     * Gives back a boolean flag signaling whether a SSL session is valid and
     * available
     * for resuming or joining or not.
     * @return true if this session may be resumed.
     */
    public boolean isValid() {
        SSLSessionContext context = sessionContext;
        if (isValid
                && context != null
                && context.getSessionTimeout() != 0
                && getCreationTime() + (context.getSessionTimeout() * 1000)
                    < System.currentTimeMillis()) {
            isValid = false;
        }
        return isValid;
    }

    /**
     * It invalidates a SSL session forbidding any resumption.
     */
    public void invalidate() {
        isValid = false;
        sessionContext = null;
    }

    /**
     * Gives back the object which is bound to the the input parameter name.
     * This name is a sort of link to the data of the SSL session's application
     * layer, if any exists. The search for this link is monitored, as a matter
     * of security, by the full machinery of the <code>AccessController</code>
     * class.
     *
     * @param name the name of the binding to find.
     * @return the value bound to that name, or null if the binding does not
     *         exist.
     * @throws <code>IllegalArgumentException</code> if the argument is null.
     */
    public Object getValue(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Parameter is null");
        }
        return values.get(name, AccessController.getContext());
    }

    /**
     * Gives back an array with the names (sort of links) of all the data
     * objects of the application layer bound into the SSL session. The search
     * for this link is monitored, as a matter of security, by the full
     * machinery of the <code>AccessController</code> class.
     *
     * @return a non-null (possibly empty) array of names of the data objects
     *         bound to this SSL session.
     */
    public String[] getValueNames() {
        Vector v = new Vector();
        AccessControlContext current = AccessController.getContext();
        AccessControlContext cont;
        for (Iterator it = values.entrySet().iterator(); it.hasNext();) {
            TwoKeyHashMap.Entry entry = (TwoKeyHashMap.Entry) it.next();
            cont = (AccessControlContext) entry.getKey2();
            if ((current == null && cont == null)
                    || (current != null && current.equals(cont))) {
                v.add(entry.getKey1());
            }
        }
        return (String[]) v.toArray(new String[0]);
    }

    /**
     * A link (name) with the specified value object of the SSL session's
     * application layer data is created or replaced. If the new (or existing)
     * value object implements the <code>SSLSessionBindingListener</code>
     * interface, that object will be notified in due course. These links-to
     * -data bounds are monitored, as a matter of security, by the full
     * machinery of the <code>AccessController</code> class.
     *
     * @param name the name of the link (no null are
     *            accepted!)
     * @param value data object that shall be bound to
     *            name.
     * @throws <code>IllegalArgumentException</code> if one or both
     *             argument(s) is null.
     */
    public void putValue(String name, Object value) {
        if (name == null || value == null) {
            throw new IllegalArgumentException("Parameter is null");
        }
        Object old = values.put(name, AccessController.getContext(), value);
        if (value instanceof SSLSessionBindingListener) {
            ((SSLSessionBindingListener) value)
                    .valueBound(new SSLSessionBindingEvent(this, name));
        }
        if (old instanceof SSLSessionBindingListener) {
            ((SSLSessionBindingListener) old)
                    .valueUnbound(new SSLSessionBindingEvent(this, name));
        }
    }

    /**
     * Removes a link (name) with the specified value object of the SSL
     * session's application layer data.
     *
     * <p>If the value object implements the <code>SSLSessionBindingListener</code>
     * interface, the object will receive a <code>valueUnbound</code> notification.
     *
     * <p>These links-to -data bounds are
     * monitored, as a matter of security, by the full machinery of the
     * <code>AccessController</code> class.
     *
     * @param name the name of the link (no null are
     *            accepted!)
     * @throws <code>IllegalArgumentException</code> if the argument is null.
     */
    public void removeValue(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Parameter is null");
        }
        Object old = values.remove(name, AccessController.getContext());
        if (old instanceof SSLSessionBindingListener) {
            SSLSessionBindingListener listener = (SSLSessionBindingListener) old;
            listener.valueUnbound(new SSLSessionBindingEvent(this, name));
        }
    }

    protected void finalize() {
        NativeCrypto.SSL_SESSION_free(sslSessionNativePointer);
    }
}