/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.tests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test certificates made with the JDK's keytool, written as PEM files the way a utility hands them out: a CA, a
 * server (EC P-256, for 127.0.0.1) and a client (RSA 2048) signed by it, and a second, untrusted CA with a
 * certificate of its own.
 */
final class Certificates {

	private static final String PASSWORD = "changeit";

	final Path ca;

	final Path serverChain;

	final Path serverKey;

	final Path clientChain;

	final Path clientKey;

	final Path rogueCa;

	final Path rogueChain;

	final Path rogueKey;

	private final Path dir;

	Certificates(final Path dir) throws IOException, InterruptedException
	{
		this.dir = dir;
		this.ca = authority("ca", "CN=Test Utility CA");
		this.rogueCa = authority("rogue-ca", "CN=Somebody Else CA");
		this.serverChain = issue("server", "CN=Test Station", "EC", "ca", "san=ip:127.0.0.1,dns:localhost");
		this.clientChain = issue("client", "CN=Test Control Centre", "RSA", "ca", null);
		this.rogueChain = issue("rogue", "CN=Rogue", "EC", "rogue-ca", "san=ip:127.0.0.1");
		this.serverKey = dir.resolve("server.key");
		this.clientKey = dir.resolve("client.key");
		this.rogueKey = dir.resolve("rogue.key");
	}

	private Path authority(final String name, final String dn) throws IOException, InterruptedException
	{
		keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", dn, "-ext",
				"bc:c", "-validity", "2", "-keystore", store(name), "-storepass", PASSWORD);
		final Path pem = this.dir.resolve(name + ".crt");
		keytool("-exportcert", "-rfc", "-alias", name, "-file", pem.toString(), "-keystore", store(name), "-storepass",
				PASSWORD);
		return pem;
	}

	/** A key and a certificate signed by {@code issuer}; returns the chain file, the key is written beside it. */
	private Path issue(final String name, final String dn, final String algorithm, final String issuer,
			final String san) throws IOException, InterruptedException
	{
		final List<String> generate = new ArrayList<>(List.of("-genkeypair", "-alias", name, "-keyalg", algorithm,
				"-dname", dn, "-validity", "2", "-keystore", store(name), "-storepass", PASSWORD));
		generate.addAll(algorithm.equals("EC") ? List.of("-groupname", "secp256r1") : List.of("-keysize", "2048"));
		keytool(generate.toArray(new String[0]));

		final Path request = this.dir.resolve(name + ".csr");
		keytool("-certreq", "-alias", name, "-file", request.toString(), "-keystore", store(name), "-storepass",
				PASSWORD);
		final Path certificate = this.dir.resolve(name + ".crt");
		final List<String> sign = new ArrayList<>(List.of("-gencert", "-rfc", "-alias", issuer, "-infile",
				request.toString(), "-outfile", certificate.toString(), "-validity", "2", "-keystore", store(issuer),
				"-storepass", PASSWORD));
		if (san != null) {
			sign.addAll(List.of("-ext", san));
		}
		keytool(sign.toArray(new String[0]));

		final Path chain = this.dir.resolve(name + "-chain.crt");
		Files.writeString(chain, Files.readString(certificate) + Files.readString(this.dir.resolve(issuer + ".crt")));
		writeKey(name);
		return chain;
	}

	/** The private key as unencrypted PKCS#8 PEM, as openssl writes it. */
	private void writeKey(final String name) throws IOException
	{
		try (InputStream in = Files.newInputStream(Path.of(store(name)))) {
			final KeyStore store = KeyStore.getInstance("PKCS12");
			store.load(in, PASSWORD.toCharArray());
			final Key key = store.getKey(name, PASSWORD.toCharArray());
			final String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
					.encodeToString(key.getEncoded());
			Files.writeString(this.dir.resolve(name + ".key"),
					"-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
		}
		catch (final java.security.GeneralSecurityException e) {
			throw new IOException(e);
		}
	}

	/** A certificate in {@code chain}, for tests that need one as an object. */
	static Certificate first(final Path chain) throws IOException
	{
		try (InputStream in = Files.newInputStream(chain)) {
			return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
		}
		catch (final java.security.cert.CertificateException e) {
			throw new IOException(e);
		}
	}

	private String store(final String name)
	{
		return this.dir.resolve(name + ".p12").toString();
	}

	private static void keytool(final String... args) throws IOException, InterruptedException
	{
		final List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
		command.addAll(List.of(args));
		final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
			throw new IOException("keytool " + String.join(" ", args) + " failed: " + output);
		}
	}
}
