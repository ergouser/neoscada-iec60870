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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.AbstractMessage;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.SetPointCommandShortFloatingPoint;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.SingleCommand;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDUAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.CauseOfTransmission;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.DoublePoint;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.StandardCause;
import org.eclipse.neoscada.protocol.iec60870.tls.TlsOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * This stack against an independent one: the core of IEC60870-5-104-Simulator (MIT), run in Docker by the
 * {@code interop} task, which builds the image from {@code interop/} first and names it in {@code oracle.image}. It
 * is the simulator as a slave read by this client, and as a master reading this server, in plain TCP and over TLS 1.2
 * and 1.3.
 */
@Tag("interop")
class InteropTest extends Fixture {

	private static final String IMAGE = System.getProperty("oracle.image", "iec104-oracle:local");

	@TempDir
	static Path dir;

	static Certificates certificates;

	private final List<String> containers = new ArrayList<>();

	@BeforeAll
	static void makeCertificates() throws Exception
	{
		certificates = new Certificates(dir);
	}

	@AfterEach
	void removeContainers() throws Exception
	{
		for (final String name : this.containers) {
			new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true).start().waitFor(30,
					TimeUnit.SECONDS);
		}
	}

	// ------------------------------------------------------------------- the simulator as slave

	@Test
	void ourClientReadsTheSimulatorSlave() throws Exception
	{
		final int port = freePort();
		startSlave(port);
		readAndCommandTheSlave(connect(new InetSocketAddress("127.0.0.1", port), null));
	}

	@Test
	void ourClientReadsTheSimulatorSlaveOverTls() throws Exception
	{
		final int port = freePort();
		startSlave(port, "--tls", "/certs/rsa-server-chain.crt", "/certs/rsa-server.key", "/certs/ca.crt");
		final TestClient c = connect(new InetSocketAddress("127.0.0.1", port), TlsTest.clientOptions(certificates)
				.build());
		readAndCommandTheSlave(c);
	}

	private void readAndCommandTheSlave(final TestClient c)
	{
		await().atMost(WAIT).until(() -> c.values.size() >= 8);
		assertEquals(Boolean.TRUE, c.values.get(1).getValue());
		assertEquals(DoublePoint.ON, c.values.get(2).getValue());
		assertEquals(0.5, (Double)c.values.get(3).getValue(), 1e-4);
		assertEquals((short)-1234, c.values.get(4).getValue());
		assertEquals(1.5f, c.values.get(5).getValue());
		assertEquals(0x12345678L, ((Number)c.values.get(6).getValue()).longValue() & 0xFFFFFFFFL);
		assertEquals(Boolean.FALSE, c.values.get(7).getValue());
		assertEquals(-40.25f, c.values.get(8).getValue());

		// a set point the simulator writes back to the measured value at the same address
		c.client.writeCommand(new SetPointCommandShortFloatingPoint(header(), InformationObjectAddress.valueOf(5),
				7.25f));
		await().atMost(WAIT).until(() -> Float.valueOf(7.25f).equals(c.values.get(5).getValue()));
		assertTrue(confirmed(c, SetPointCommandShortFloatingPoint.class), () -> "no positive ACTCON: " + c.messages);

		// select, then execute
		c.client.writeCommand(new SingleCommand(header(), InformationObjectAddress.valueOf(1), false, (byte)0,
				false));
		await().atMost(WAIT).until(() -> confirmed(c, SingleCommand.class));
		c.client.writeCommand(new SingleCommand(header(), InformationObjectAddress.valueOf(1), false, (byte)0,
				true));
		await().atMost(WAIT).until(() -> Boolean.FALSE.equals(c.values.get(1).getValue()));
	}

	// ------------------------------------------------------------------- the simulator as master

	@Test
	void theSimulatorMasterReadsOurServer() throws Exception
	{
		final InetSocketAddress address = startServer(null);
		checkMasterRun(runMaster(address));
	}

	@Test
	void theSimulatorMasterReadsOurServerOverTls13() throws Exception
	{
		final InetSocketAddress address = startServer(TlsTest.serverOptions(certificates).build());
		checkMasterRun(runMaster(address, "--tls", "/certs/client-chain.crt", "/certs/client.key", "/certs/ca.crt"));
	}

	@Test
	void theSimulatorMasterReadsOurServerOverTls12() throws Exception
	{
		final InetSocketAddress address = startServer(TlsTest.serverOptions(certificates).protocols("TLSv1.2")
				.build());
		checkMasterRun(runMaster(address, "--tls", "/certs/client-chain.crt", "/certs/client.key", "/certs/ca.crt",
				"--tls12"));
	}

	private void checkMasterRun(final List<String> lines)
	{
		assertTrue(lines.contains("POINT 100 M_ME_NC_1 1.5"), () -> String.join("\n", lines));
		assertTrue(lines.contains("POINT 101 M_ME_NC_1 -230.25"), () -> String.join("\n", lines));
		assertTrue(lines.contains("POINT 200 M_SP_NA_1 true"), () -> String.join("\n", lines));
		assertTrue(lines.contains("POINT 201 M_SP_NA_1 false"), () -> String.join("\n", lines));
		assertTrue(lines.contains("DONE"), () -> String.join("\n", lines));
		assertEquals(List.of(new TestStation.Command(102, 7.25f, (byte)0, true),
				new TestStation.Command(200, false, (byte)0, false), new TestStation.Command(200, false, (byte)0, true)),
				this.station.commands);
	}

	// ------------------------------------------------------------------- docker

	private void startSlave(final int port, final String... tls) throws IOException
	{
		final List<String> lines = new CopyOnWriteArrayList<>();
		final List<String> args = new ArrayList<>(List.of("slave", String.valueOf(port)));
		args.addAll(List.of(tls));
		final Process process = docker(args);
		reader(process, lines).start();
		await().atMost(Duration.ofSeconds(30)).until(() -> lines.contains("READY") || !process.isAlive());
		assertTrue(lines.contains("READY"), () -> "the slave did not start: " + lines);
	}

	private List<String> runMaster(final InetSocketAddress address, final String... tls) throws Exception
	{
		final List<String> lines = new CopyOnWriteArrayList<>();
		final List<String> args = new ArrayList<>(List.of("master", "127.0.0.1", String.valueOf(address.getPort())));
		args.addAll(List.of(tls));
		final Process process = docker(args);
		final Thread reader = reader(process, lines);
		reader.start();
		assertTrue(process.waitFor(60, TimeUnit.SECONDS), () -> "the master did not finish: " + lines);
		reader.join(5000);
		return lines;
	}

	private Process docker(final List<String> args) throws IOException
	{
		final String name = "iec104-oracle-" + UUID.randomUUID();
		this.containers.add(name);
		final List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "--init", "--name", name,
				"--network", "host", "-v", dir.toAbsolutePath() + ":/certs:ro", IMAGE));
		command.addAll(args);
		return new ProcessBuilder(command).redirectErrorStream(true).start();
	}

	private static Thread reader(final Process process, final List<String> lines)
	{
		final Thread thread = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					lines.add(line.trim());
				}
			}
			catch (final IOException e) {
				lines.add("! " + e);
			}
		}, "oracle-output");
		thread.setDaemon(true);
		return thread;
	}

	private static int freePort() throws IOException
	{
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static ASDUHeader header()
	{
		return new ASDUHeader(new CauseOfTransmission(StandardCause.ACTIVATED), ASDUAddress.valueOf(1));
	}

	private static boolean confirmed(final TestClient c, final Class<?> type)
	{
		return c.messages.stream().anyMatch(m -> type.isInstance(m)
				&& ((AbstractMessage)m).getHeader().getCauseOfTransmission().getCause() == StandardCause.ACTIVATION_CONFIRM
				&& ((AbstractMessage)m).getHeader().getCauseOfTransmission().isPositive());
	}
}
