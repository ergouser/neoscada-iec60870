/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.tls;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertPathValidator;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandler;

/**
 * TLS for IEC 60870-5-104, following the profile of IEC 62351-3: TLS 1.2 or 1.3 only, both ends authenticated by
 * certificate, only cipher suites with forward secrecy and authenticated encryption, the session keys renewed on a
 * schedule, and a limit on how long a session may be resumed.
 * <p>
 * Every rule of the profile is a default here that can be changed, because real stations differ: some still offer
 * only TLS 1.2, some name their own suites in the utility's interoperability list. Nothing below TLS 1.2 can be
 * enabled.
 * </p>
 * <p>
 * The port IEC 62351-3 gives 104 over TLS is {@link #DEFAULT_PORT}, 19998, instead of 2404.
 * </p>
 *
 * <pre>
 * TlsOptions tls = TlsOptions.builder()
 * 		.identity(Path.of("station.crt"), Path.of("station.key"), null)
 * 		.trust(Path.of("utility-ca.crt"))
 * 		.build();
 * Server server = new Server(new InetSocketAddress(TlsOptions.DEFAULT_PORT), options, modules, tls);
 * </pre>
 */
public final class TlsOptions {

	/** The port IEC 62351-3 assigns to IEC 60870-5-104 over TLS. */
	public static final int DEFAULT_PORT = 19998;

	/** TLS 1.3, then 1.2. */
	public static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

	/**
	 * Suites with ephemeral key exchange and AEAD encryption: the three TLS 1.3 suites, and the ECDHE AES-GCM and
	 * ChaCha20 suites of TLS 1.2. Those a JDK does not offer are left out when the engine is made.
	 */
	public static final List<String> DEFAULT_CIPHER_SUITES = List.of(
			// TLS 1.3
			"TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256", "TLS_CHACHA20_POLY1305_SHA256",
			// TLS 1.2
			"TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
			"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
			"TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");

	/** How often the session keys are renewed unless told otherwise. */
	public static final Duration DEFAULT_KEY_RENEWAL = Duration.ofHours(1);

	/** How long a session may be resumed unless told otherwise. */
	public static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofHours(1);

	private static final Set<String> ALLOWED_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");

	private final SSLContext context;

	private final boolean requireClientAuth;

	private final List<String> protocols;

	private final List<String> cipherSuites;

	private final Duration keyRenewal;

	private final Duration sessionLifetime;

	private final String expectedServerName;

	private TlsOptions(final Builder builder) throws GeneralSecurityException
	{
		this.context = builder.context != null ? builder.context : builder.buildContext();
		this.requireClientAuth = builder.requireClientAuth;
		this.protocols = List.copyOf(builder.protocols);
		this.cipherSuites = List.copyOf(builder.cipherSuites);
		this.keyRenewal = builder.keyRenewal;
		this.sessionLifetime = builder.sessionLifetime;
		this.expectedServerName = builder.expectedServerName;

		final int seconds = (int)Math.min(Integer.MAX_VALUE, this.sessionLifetime.toSeconds());
		this.context.getClientSessionContext().setSessionTimeout(seconds);
		this.context.getServerSessionContext().setSessionTimeout(seconds);
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public SSLContext getContext()
	{
		return this.context;
	}

	/** Whether a server refuses a client that presents no certificate; true unless told otherwise. */
	public boolean isRequireClientAuth()
	{
		return this.requireClientAuth;
	}

	public List<String> getProtocols()
	{
		return this.protocols;
	}

	public List<String> getCipherSuites()
	{
		return this.cipherSuites;
	}

	/** How often the session keys are renewed; zero for never. */
	public Duration getKeyRenewal()
	{
		return this.keyRenewal;
	}

	public Duration getSessionLifetime()
	{
		return this.sessionLifetime;
	}

	/** The name a client requires in the server's certificate, or null to accept any certificate it trusts. */
	public String getExpectedServerName()
	{
		return this.expectedServerName;
	}

	/**
	 * An engine for one connection.
	 *
	 * @param peerHost
	 *            the host connected to, for a client; null for a server
	 */
	public SSLEngine newEngine(final boolean client, final String peerHost, final int peerPort)
	{
		// the name the server's certificate is checked against is the engine's peer host
		final String host = this.expectedServerName != null ? this.expectedServerName : peerHost;
		final SSLEngine engine = client && host != null ? this.context.createSSLEngine(host, peerPort)
				: this.context.createSSLEngine();
		engine.setUseClientMode(client);

		final SSLParameters parameters = engine.getSSLParameters();
		parameters.setProtocols(supported(this.protocols, engine.getSupportedProtocols(), "protocol"));
		parameters.setCipherSuites(supported(this.cipherSuites, engine.getSupportedCipherSuites(), "cipher suite"));
		parameters.setUseCipherSuitesOrder(true);
		if (client) {
			if (this.expectedServerName != null) {
				// the same check HTTPS makes: the name must be in the certificate's subject alternative names
				parameters.setEndpointIdentificationAlgorithm("HTTPS");
				if (!this.expectedServerName.matches("[0-9.:]+")) {
					parameters.setServerNames(List.of(new SNIHostName(this.expectedServerName)));
				}
			}
		}
		else {
			parameters.setNeedClientAuth(this.requireClientAuth);
		}
		engine.setSSLParameters(parameters);
		return engine;
	}

	/**
	 * Puts TLS at the front of a connection's pipeline: the handler that encrypts, and the one that renews its keys.
	 *
	 * @return the handler that encrypts, whose {@link SslHandler#handshakeFuture()} completes once the peer is
	 *         authenticated
	 */
	public SslHandler install(final ChannelPipeline pipeline, final ByteBufAllocator allocator, final boolean client,
			final String peerHost, final int peerPort)
	{
		final SslHandler ssl = new SslHandler(newEngine(client, peerHost, peerPort));
		pipeline.addFirst(TlsKeyRenewal.NAME, new TlsKeyRenewal(ssl, this.keyRenewal));
		pipeline.addFirst("tls", ssl);
		return ssl;
	}

	private static String[] supported(final List<String> wanted, final String[] supported, final String what)
	{
		final List<String> available = Arrays.asList(supported);
		final List<String> result = new ArrayList<>();
		for (final String name : wanted) {
			if (available.contains(name)) {
				result.add(name);
			}
		}
		if (result.isEmpty()) {
			throw new IllegalStateException(String.format("None of the %ss %s is supported here", what, wanted));
		}
		return result.toArray(new String[0]);
	}

	@Override
	public String toString()
	{
		return String.format("TlsOptions[protocols=%s, requireClientAuth=%s, keyRenewal=%s, sessionLifetime=%s%s]",
				this.protocols, this.requireClientAuth, this.keyRenewal, this.sessionLifetime,
				this.expectedServerName != null ? ", server=" + this.expectedServerName : "");
	}

	/** What a revocation check consults: nothing, or the certificate's OCSP responder and CRL distribution points. */
	public enum Revocation
	{
		/** No revocation check; the default, since a station is often not able to reach the responder. */
		NONE,
		/** OCSP first, then the CRL; a certificate whose status cannot be found is refused. */
		REQUIRED,
		/** As {@link #REQUIRED}, but a certificate whose status cannot be found is accepted. */
		SOFT_FAIL
	}

	public static final class Builder {

		private SSLContext context;

		private KeyStore identity;

		private char[] identityPassword;

		private KeyStore trusted;

		private Revocation revocation = Revocation.NONE;

		private boolean requireClientAuth = true;

		private List<String> protocols = DEFAULT_PROTOCOLS;

		private List<String> cipherSuites = DEFAULT_CIPHER_SUITES;

		private Duration keyRenewal = DEFAULT_KEY_RENEWAL;

		private Duration sessionLifetime = DEFAULT_SESSION_LIFETIME;

		private String expectedServerName;

		private Builder()
		{
		}

		/**
		 * A context made elsewhere; the identity, trust and revocation set here are then not used. Its session
		 * contexts are given this builder's session lifetime.
		 */
		public Builder context(final SSLContext context)
		{
			this.context = Objects.requireNonNull(context);
			return this;
		}

		/** The certificate this end presents, and its key, from a key store. */
		public Builder identity(final KeyStore store, final char[] password)
		{
			this.identity = Objects.requireNonNull(store);
			this.identityPassword = password != null ? password.clone() : new char[0];
			return this;
		}

		/**
		 * The certificate this end presents, and its key, from PEM files.
		 *
		 * @param certificates
		 *            the certificate, followed by the authorities that signed it if the peer does not have them
		 * @param password
		 *            the key's password, or null for a key not encrypted
		 */
		public Builder identity(final Path certificates, final Path key, final char[] password) throws IOException
		{
			final List<X509Certificate> chain = Pem.readCertificates(certificates);
			final PrivateKey privateKey = Pem.readPrivateKey(key, password);
			try {
				final KeyStore store = emptyStore();
				store.setKeyEntry("identity", privateKey, new char[0], chain.toArray(new X509Certificate[0]));
				return identity(store, new char[0]);
			}
			catch (final GeneralSecurityException e) {
				throw new IOException("Unable to use the key in " + key + ": " + e.getMessage(), e);
			}
		}

		/** The certificates, usually authorities, that a peer's certificate must chain to. */
		public Builder trust(final KeyStore store)
		{
			this.trusted = Objects.requireNonNull(store);
			return this;
		}

		/** The certificates, usually authorities, that a peer's certificate must chain to, from PEM files. */
		public Builder trust(final Path... files) throws IOException
		{
			try {
				final KeyStore store = emptyStore();
				int index = 0;
				for (final Path file : files) {
					for (final X509Certificate certificate : Pem.readCertificates(file)) {
						store.setCertificateEntry("trusted" + index++, certificate);
					}
				}
				return trust(store);
			}
			catch (final GeneralSecurityException e) {
				throw new IOException("Unable to trust " + Arrays.toString(files) + ": " + e.getMessage(), e);
			}
		}

		public Builder revocation(final Revocation revocation)
		{
			this.revocation = Objects.requireNonNull(revocation);
			return this;
		}

		/** Whether a server refuses a client that presents no certificate. IEC 62351-3 requires it. */
		public Builder requireClientAuth(final boolean requireClientAuth)
		{
			this.requireClientAuth = requireClientAuth;
			return this;
		}

		/**
		 * The protocol versions offered, in order of preference.
		 *
		 * @throws IllegalArgumentException
		 *             for anything but TLSv1.2 and TLSv1.3
		 */
		public Builder protocols(final String... protocols)
		{
			for (final String protocol : protocols) {
				if (!ALLOWED_PROTOCOLS.contains(protocol)) {
					throw new IllegalArgumentException(
							String.format("%s is not allowed; IEC 62351-3 permits only TLSv1.2 and TLSv1.3", protocol));
				}
			}
			if (protocols.length == 0) {
				throw new IllegalArgumentException("At least one protocol version is needed");
			}
			this.protocols = List.of(protocols);
			return this;
		}

		/** The cipher suites offered, in order of preference, in their JSSE names. */
		public Builder cipherSuites(final String... cipherSuites)
		{
			if (cipherSuites.length == 0) {
				throw new IllegalArgumentException("At least one cipher suite is needed");
			}
			this.cipherSuites = List.of(cipherSuites);
			return this;
		}

		/**
		 * How often the session keys are renewed on a connection: a key update in TLS 1.3, a renegotiation in TLS 1.2.
		 * Zero turns it off.
		 */
		public Builder keyRenewal(final Duration keyRenewal)
		{
			if (keyRenewal.isNegative()) {
				throw new IllegalArgumentException("A key renewal interval cannot be negative");
			}
			this.keyRenewal = keyRenewal;
			return this;
		}

		/** How long after its handshake a session may be resumed by a new connection. */
		public Builder sessionLifetime(final Duration sessionLifetime)
		{
			if (sessionLifetime.isNegative() || sessionLifetime.isZero()) {
				throw new IllegalArgumentException("A session lifetime must be positive");
			}
			this.sessionLifetime = sessionLifetime;
			return this;
		}

		/**
		 * For a client: the host name or address the server's certificate must carry in its subject alternative
		 * names. Without one, any certificate that chains to a trusted one is accepted, as is usual for 104, where
		 * stations are addressed by IP.
		 */
		public Builder expectedServerName(final String name)
		{
			this.expectedServerName = name;
			return this;
		}

		/**
		 * @throws GeneralSecurityException
		 *             when the identity or trust cannot be used
		 * @throws IllegalStateException
		 *             when neither a context nor what to trust was given
		 */
		public TlsOptions build() throws GeneralSecurityException
		{
			return new TlsOptions(this);
		}

		private SSLContext buildContext() throws GeneralSecurityException
		{
			if (this.trusted == null) {
				throw new IllegalStateException("TLS needs the certificates to trust: call trust(...) or context(...)");
			}
			KeyManagerFactory keys = null;
			if (this.identity != null) {
				keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				keys.init(this.identity, this.identityPassword);
			}

			final TrustManagerFactory trust = TrustManagerFactory.getInstance("PKIX");
			if (this.revocation == Revocation.NONE) {
				trust.init(this.trusted);
			}
			else {
				final PKIXBuilderParameters parameters = new PKIXBuilderParameters(this.trusted, new X509CertSelector());
				final PKIXRevocationChecker checker = (PKIXRevocationChecker)CertPathValidator.getInstance("PKIX")
						.getRevocationChecker();
				final EnumSet<PKIXRevocationChecker.Option> options = EnumSet.noneOf(PKIXRevocationChecker.Option.class);
				if (this.revocation == Revocation.SOFT_FAIL) {
					options.add(PKIXRevocationChecker.Option.SOFT_FAIL);
				}
				checker.setOptions(options);
				parameters.addCertPathChecker(checker);
				parameters.setRevocationEnabled(true);
				trust.init(new CertPathTrustManagerParameters(parameters));
			}

			final SSLContext context = SSLContext.getInstance("TLS");
			context.init(keys != null ? keys.getKeyManagers() : null, trust.getTrustManagers(), null);
			return context;
		}

		private static KeyStore emptyStore() throws GeneralSecurityException, IOException
		{
			final KeyStore store = KeyStore.getInstance("PKCS12");
			store.load(null, null);
			return store;
		}
	}
}
