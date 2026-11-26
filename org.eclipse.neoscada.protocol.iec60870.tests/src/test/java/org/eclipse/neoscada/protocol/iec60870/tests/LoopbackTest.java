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
import org.junit.jupiter.api.Test;

/**
 * A client and a server of this stack talking over loopback TCP: start of data transfer, general interrogation,
 * spontaneous data, and commands confirmed and terminated. {@link TlsTest} runs the same over TLS.
 */
class LoopbackTest extends Fixture {
	@Test
	void interrogationReturnsEveryPoint()
	{
		final TestClient c = connect(startServer(null), null);
		exchange(c, this.station);
	}

	/** The whole exchange, for this suite and {@link TlsTest}. */
	static void exchange(final TestClient c, final TestStation station)
	{
		await().atMost(WAIT).until(() -> c.values.size() == 5);
		assertEquals(1.5f, c.values.get(100).getValue());
		assertEquals(-230.25f, c.values.get(101).getValue());
		assertEquals(Boolean.TRUE, c.values.get(200).getValue());
		assertEquals(Boolean.FALSE, c.values.get(201).getValue());
		assertEquals(1, c.started.get());

		// spontaneous
		station.set(102, 42.5f);
		await().atMost(WAIT).until(() -> Float.valueOf(42.5f).equals(c.values.get(102).getValue()));

		// a set point, confirmed and terminated
		c.client.writeCommand(new SetPointCommandShortFloatingPoint(header(), InformationObjectAddress.valueOf(102),
				7.25f));
		await().atMost(WAIT).until(() -> has(c, SetPointCommandShortFloatingPoint.class,
				StandardCause.ACTIVATION_TERMINATION));
		assertEquals(new TestStation.Command(102, 7.25f, (byte)0, true), station.commands.get(0));
		has(c, SetPointCommandShortFloatingPoint.class, StandardCause.ACTIVATION_CONFIRM);

		// select, then execute
		c.client.writeCommand(new SingleCommand(header(), InformationObjectAddress.valueOf(200), false, (byte)0,
				false));
		await().atMost(WAIT).until(() -> station.commands.size() == 2);
		c.client.writeCommand(new SingleCommand(header(), InformationObjectAddress.valueOf(200), false, (byte)0,
				true));
		await().atMost(WAIT).until(() -> has(c, SingleCommand.class, StandardCause.ACTIVATION_TERMINATION));
		assertEquals(new TestStation.Command(200, false, (byte)0, false), station.commands.get(1));
		assertEquals(new TestStation.Command(200, false, (byte)0, true), station.commands.get(2));
	}

	private static ASDUHeader header()
	{
		return new ASDUHeader(new CauseOfTransmission(StandardCause.ACTIVATED), TestStation.ADDRESS);
	}

	private static boolean has(final TestClient c, final Class<?> type, final StandardCause cause)
	{
		final Predicate<Object> match = m -> type.isInstance(m)
				&& ((AbstractMessage)m).getHeader().getCauseOfTransmission().getCause() == cause
				&& ((AbstractMessage)m).getHeader().getCauseOfTransmission().isPositive();
		return c.messages.stream().anyMatch(match);
	}
}
