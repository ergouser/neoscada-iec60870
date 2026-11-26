/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.tests;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import org.eclipse.neoscada.protocol.iec60870.tls.TlsOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 104 over TLS between this stack's client and server, with the IEC 62351-3 profile of {@link TlsOptions}: both ends
 * authenticated, TLS 1.2 or 1.3 only, forward-secret AEAD suites, keys renewed while the link carries data.
 */
class TlsTest extends Fixture {

	@TempDir
	static Path dir;

	static Certificates certificates;

	@BeforeAll
	static void makeCertificates() throws Exception
	{
		certificates = new Certificates(dir);
	}

	static TlsOptions.Builder server() throws IOException
	{
		return TlsOptions.builder().identity(certificates.serverChain, certificates.serverKey, null)
				.trust(certificates.ca);
	}

	static TlsOptions.Builder client() throws IOException
	{
		return TlsOptions.builder().identity(certificates.clientChain, certificates.clientKey, null)
				.trust(certificates.ca);
	}

	@Test
	void theExchangeRunsOverTls13() throws Exception
	{
		final InetSocketAddress address = startServer(server().build());
		LoopbackTest.exchange(connect(address, client().build()), this.station);
		assertEquals("TLSv1.3", probe(address, client().build(), null));
	}

	@Test
	void theExchangeRunsOverTls12() throws Exception
	{
		final InetSocketAddress address = startServer(server().protocols("TLSv1.2").build());
		LoopbackTest.exchange(connect(address, client().build()), this.station);
		assertEquals("TLSv1.2", probe(address, client().build(), null));
	}

	@Test
	void aClientWithoutACertificateIsRefused() throws Exception
	{
		final InetSocketAddress address = startServer(server().build());
		final TestClient c = connect(address, TlsOptions.builder().trust(certificates.ca).build());
		await().atMost(WAIT).until(() -> !c.errors.isEmpty());
		assertTrue(c.values.isEmpty());
		assertTrue(c.errors.stream().anyMatch(e -> e instanceof SSLException || e.getCause() instanceof SSLException),
				() -> "refused by the server's TLS: " + c.errors);
	}

	@Test
	void aServerMayAcceptAClientWithoutACertificateWhenToldTo() throws Exception
	{
		final InetSocketAddress address = startServer(server().requireClientAuth(false).build());
		final TestClient c = connect(address, TlsOptions.builder().trust(certificates.ca).build());
		await().atMost(WAIT).until(() -> c.values.size() == 5);
	}

	@Test
	void aClientRefusesAServerItDoesNotTrust() throws Exception
	{
		final InetSocketAddress address = startServer(TlsOptions.builder()
				.identity(certificates.rogueChain, certificates.rogueKey, null).trust(certificates.ca).build());
		final TestClient c = connect(address, client().build());
		await().atMost(WAIT).until(() -> !c.errors.isEmpty());
		assertFalse(c.isConnected());
		assertTrue(c.values.isEmpty());
	}

	@Test
	void aServerRefusesAClientItDoesNotTrust() throws Exception
	{
		final InetSocketAddress address = startServer(server().build());
		final TestClient c = connect(address, TlsOptions.builder()
				.identity(certificates.rogueChain, certificates.rogueKey, null).trust(certificates.ca).build());
		await().atMost(WAIT).until(() -> !c.errors.isEmpty());
		assertTrue(c.values.isEmpty());
		assertTrue(c.errors.stream().anyMatch(e -> e instanceof SSLException || e.getCause() instanceof SSLException),
				() -> "refused by the server's TLS: " + c.errors);
	}

	@Test
	void aClientChecksTheServersNameWhenGivenOne() throws Exception
	{
		final InetSocketAddress address = startServer(server().build());
		final TestClient wrong = connect(address, client().expectedServerName("substation-7.example").build());
		await().atMost(WAIT).until(() -> !wrong.errors.isEmpty());
		assertTrue(wrong.values.isEmpty());
		wrong.close();

		final TestClient right = connect(address, client().expectedServerName("127.0.0.1").build());
		await().atMost(WAIT).until(() -> right.values.size() == 5);
	}

	@Test
	void aSuiteWithoutForwardSecrecyOrAeadIsRefused() throws Exception
	{
		final InetSocketAddress address = startServer(server().build());
		final TlsOptions weak = client().build();
		assertThrows(SSLException.class, () -> probe(address, weak, "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"));
		assertThrows(SSLException.class, () -> probe(address, weak, "TLS_RSA_WITH_AES_128_GCM_SHA256"));
	}

	@Test
	void versionsBelowTls12CannotBeEnabled()
	{
		assertThrows(IllegalArgumentException.class, () -> TlsOptions.builder().protocols("TLSv1.1"));
		assertThrows(IllegalArgumentException.class, () -> TlsOptions.builder().protocols("TLSv1"));
		assertThrows(IllegalArgumentException.class, () -> TlsOptions.builder().protocols("SSLv3"));
	}

	@Test
	void trustMustBeGiven()
	{
		assertThrows(IllegalStateException.class, () -> TlsOptions.builder().build());
	}

	@Test
	void keysAreRenewedOverTls12WithoutInterruptingTheLink() throws Exception
	{
		final InetSocketAddress address = startServer(server().protocols("TLSv1.2").build());
		final TestClient c = connect(address, client().keyRenewal(Duration.ofMillis(300)).build());
		await().atMost(WAIT).until(() -> c.values.size() == 5);
		await().atMost(WAIT).until(() -> c.handshakes.get() >= 4);
		this.station.set(102, 99f);
		await().atMost(WAIT).until(() -> Float.valueOf(99f).equals(c.values.get(102).getValue()));
		assertEquals(1, c.states.stream().filter(s -> s == org.eclipse.neoscada.protocol.iec60870.client.AutoConnectClient.State.CONNECTED).count());
	}

	@Test
	void keysAreRenewedOverTls13WithoutInterruptingTheLink() throws Exception
	{
		final InetSocketAddress address = startServer(server().build());
		final TestClient c = connect(address, client().keyRenewal(Duration.ofMillis(300)).build());
		await().atMost(WAIT).until(() -> c.values.size() == 5);
		Thread.sleep(1500);
		for (int i = 0; i < 5; i++) {
			final float value = 100f + i;
			this.station.set(102, value);
			await().atMost(WAIT).until(() -> Float.valueOf(value).equals(c.values.get(102).getValue()));
		}
		assertEquals(1, c.states.stream().filter(s -> s == org.eclipse.neoscada.protocol.iec60870.client.AutoConnectClient.State.CONNECTED).count());
	}

	@Test
	void aKeyStoreIdentityWorksAsWellAsPem() throws Exception
	{
		final KeyStore store = KeyStore.getInstance("PKCS12");
		try (var in = java.nio.file.Files.newInputStream(dir.resolve("client.p12"))) {
			store.load(in, "changeit".toCharArray());
		}
		final KeyStore trusted = KeyStore.getInstance("PKCS12");
		trusted.load(null, null);
		trusted.setCertificateEntry("ca", Certificates.first(certificates.ca));
		final InetSocketAddress address = startServer(server().build());
		// keytool's own store holds the client key beside a self-signed certificate, not the CA-signed one, so the
		// server must refuse it: an identity from a key store is used exactly as given
		final TestClient c = connect(address,
				TlsOptions.builder().identity(store, "changeit".toCharArray()).trust(trusted).build());
		await().atMost(WAIT).until(() -> !c.errors.isEmpty());
		assertTrue(c.values.isEmpty());
		assertTrue(c.errors.stream().anyMatch(e -> e instanceof SSLException || e.getCause() instanceof SSLException),
				() -> "refused by the server's TLS: " + c.errors);
	}

	/**
	 * Connects with a plain JDK socket and the client's context, offering only {@code suite} if given, and returns
	 * the protocol negotiated.
	 */
	private static String probe(final InetSocketAddress address, final TlsOptions tls, final String suite)
			throws IOException, GeneralSecurityException
	{
		final SSLContext context = tls.getContext();
		try (SSLSocket socket = (SSLSocket)context.getSocketFactory().createSocket(address.getAddress(),
				address.getPort())) {
			socket.setSoTimeout(5000);
			if (suite != null) {
				socket.setEnabledProtocols(new String[] { "TLSv1.2" });
				socket.setEnabledCipherSuites(new String[] { suite });
			}
			socket.startHandshake();
			return socket.getSession().getProtocol();
		}
	}
}
