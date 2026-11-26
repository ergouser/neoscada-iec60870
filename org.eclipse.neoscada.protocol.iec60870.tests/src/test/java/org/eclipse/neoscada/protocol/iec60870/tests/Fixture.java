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

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import org.eclipse.neoscada.protocol.iec60870.ProtocolOptions;
import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.AbstractMessage;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.SetPointCommandShortFloatingPoint;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.SingleCommand;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.CauseOfTransmission;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.StandardCause;
import org.eclipse.neoscada.protocol.iec60870.server.Server;
import org.eclipse.neoscada.protocol.iec60870.server.data.DataModule;
import org.eclipse.neoscada.protocol.iec60870.server.data.DataModuleOptions;
import org.eclipse.neoscada.protocol.iec60870.tls.TlsOptions;
import org.junit.jupiter.api.AfterEach;

/** A {@link TestStation} served by this stack's server, and a {@link TestClient} connected to it. */
abstract class Fixture {


	static final Duration WAIT = Duration.ofSeconds(10);

	TestStation station;

	Server server;

	TestClient client;

	/** Starts a server on a port of the system's choosing, over TLS when {@code tls} is given. */
	InetSocketAddress startServer(final TlsOptions tls)
	{
		this.station = new TestStation();
		this.station.start();
		this.server = new Server(new InetSocketAddress("127.0.0.1", 0), new ProtocolOptions.Builder().build(),
				List.of(new DataModule(new DataModuleOptions.Builder().build(), this.station)), tls);
		return (InetSocketAddress)this.server.getLocalAddress();
	}

	TestClient connect(final InetSocketAddress address, final TlsOptions tls)
	{
		this.client = new TestClient(address, new ProtocolOptions.Builder().build(), tls);
		return this.client;
	}

	@AfterEach
	void tearDown() throws Exception
	{
		if (this.client != null) {
			this.client.close();
		}
		if (this.server != null) {
			this.server.closeAndWait();
		}
		if (this.station != null) {
			this.station.stop();
		}
	}

}
