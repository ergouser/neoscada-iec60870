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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.AbstractMessage;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.CounterInterrogationCommand;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.InterrogationCommand;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.IntegratedTotalsTimeSingle;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.BinaryCounterReading;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.CauseOfTransmission;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.StandardCause;
import org.junit.jupiter.api.Test;

/**
 * Counter interrogation (C_CI_NA_1) and integrated totals (M_IT) between this stack's client and server: confirmed,
 * the readings with cause 37 or the group's, terminated; freezes that change what a read returns; requests the
 * station refuses.
 */
class CounterTest extends Fixture {

	@Test
	void aReadReturnsEveryCounterBetweenConfirmationAndTermination()
	{
		final TestClient c = started();
		c.client.writeCommand(counter(CounterInterrogationCommand.REQUEST_GENERAL, CounterInterrogationCommand.FREEZE_READ));
		final List<Object> run = run(c, CounterInterrogationCommand.class);
		assertEquals(StandardCause.ACTIVATION_CONFIRM, cause(run.get(0)));
		assertEquals(StandardCause.ACTIVATION_TERMINATION, cause(run.get(run.size() - 1)));
		final List<IntegratedTotalsTimeSingle> totals = totals(run);
		assertFalse(totals.isEmpty());
		totals.forEach(t -> assertEquals(StandardCause.COUNTER_GENERAL, cause(t)));
		assertEquals(new BinaryCounterReading(1000, 0, false, false), c.values.get(300).getValue());
		assertEquals(new BinaryCounterReading(-5, 0, false, false), c.values.get(301).getValue());
	}

	@Test
	void aGroupReadReturnsOnlyThatGroupWithItsCause()
	{
		final TestClient c = started();
		c.client.writeCommand(counter(2, CounterInterrogationCommand.FREEZE_READ));
		final List<IntegratedTotalsTimeSingle> totals = totals(run(c, CounterInterrogationCommand.class));
		assertEquals(1, totals.size());
		assertEquals(StandardCause.COUNTER_GROUP_2, cause(totals.get(0)));
		assertEquals(301, totals.get(0).getEntries().get(0).getAddress().getAddress());
	}

	@Test
	void aFreezeIsConfirmedAndTerminatedAndChangesWhatAReadReturns()
	{
		final TestClient c = started();
		this.station.count(300, 234);
		c.client.writeCommand(counter(CounterInterrogationCommand.REQUEST_GENERAL,
				CounterInterrogationCommand.FREEZE_WITH_RESET));
		final List<Object> freeze = run(c, CounterInterrogationCommand.class);
		assertEquals(2, freeze.size(), () -> "confirmation and termination only: " + freeze);
		assertTrue(((AbstractMessage)freeze.get(0)).getHeader().getCauseOfTransmission().isPositive());

		c.received.clear();
		c.client.writeCommand(counter(CounterInterrogationCommand.REQUEST_GENERAL, CounterInterrogationCommand.FREEZE_READ));
		run(c, CounterInterrogationCommand.class);
		assertEquals(new BinaryCounterReading(1234, 1, false, false), c.values.get(300).getValue());

		// the reset counted from zero: a second freeze holds only what came since
		this.station.count(300, 7);
		c.received.clear();
		c.client.writeCommand(counter(CounterInterrogationCommand.REQUEST_GENERAL,
				CounterInterrogationCommand.FREEZE_NO_RESET));
		run(c, CounterInterrogationCommand.class);
		c.received.clear();
		c.client.writeCommand(counter(1, CounterInterrogationCommand.FREEZE_READ));
		run(c, CounterInterrogationCommand.class);
		assertEquals(new BinaryCounterReading(7, 2, false, false), c.values.get(300).getValue());
	}

	@Test
	void anUnknownRequestIsRefused()
	{
		final TestClient c = started();
		c.client.writeCommand(new CounterInterrogationCommand(header(), CounterInterrogationCommand.qualifier(9, 0)));
		await().atMost(WAIT).until(() -> c.messages.stream().anyMatch(CounterInterrogationCommand.class::isInstance));
		final AbstractMessage reply = (AbstractMessage)c.messages.stream()
				.filter(CounterInterrogationCommand.class::isInstance).findFirst().get();
		assertEquals(StandardCause.ACTIVATION_CONFIRM, cause(reply));
		assertFalse(reply.getHeader().getCauseOfTransmission().isPositive());
	}

	@Test
	void anUnknownCommonAddressIsRefused()
	{
		final TestClient c = started();
		c.client.writeCommand(new CounterInterrogationCommand(
				new ASDUHeader(new CauseOfTransmission(StandardCause.ACTIVATED),
						org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDUAddress.valueOf(7)),
				CounterInterrogationCommand.qualifier(5, 0)));
		await().atMost(WAIT).until(() -> c.messages.stream().anyMatch(CounterInterrogationCommand.class::isInstance));
		final AbstractMessage reply = (AbstractMessage)c.messages.stream()
				.filter(CounterInterrogationCommand.class::isInstance).findFirst().get();
		assertEquals(StandardCause.UNKNOWN_ASDU_ADDRESS, cause(reply));
		assertFalse(reply.getHeader().getCauseOfTransmission().isPositive());
	}

	@Test
	void aDeactivationIsConfirmedNegativelyAndStartsNothing()
	{
		final TestClient c = started();
		c.client.writeCommand(new CounterInterrogationCommand(
				new ASDUHeader(new CauseOfTransmission(StandardCause.DEACTIVATED), TestStation.ADDRESS),
				CounterInterrogationCommand.qualifier(5, 0)));
		c.client.writeCommand(new InterrogationCommand(
				new ASDUHeader(new CauseOfTransmission(StandardCause.DEACTIVATED), TestStation.ADDRESS), (short)20));
		await().atMost(WAIT).until(() -> c.messages.size() >= 2);
		for (final Object reply : c.messages) {
			assertEquals(StandardCause.DEACTIVATION_CONFIRM, cause(reply));
			assertFalse(((AbstractMessage)reply).getHeader().getCauseOfTransmission().isPositive());
		}
		assertTrue(c.received.stream().noneMatch(m -> m instanceof AbstractMessage && !c.messages.contains(m)),
				() -> "nothing but the two confirmations: " + c.received);
	}

	/**
	 * Groups 10 to 16 were not recognised as interrogation causes, so the termination overtook data still waiting for
	 * the send window.
	 */
	@Test
	void aGroupInterrogationAboveNineEndsAfterItsData()
	{
		final TestClient c = started();
		this.station.addFloats(1000);
		c.received.clear();
		c.client.writeCommand(new InterrogationCommand(header(), (short)30));
		final List<Object> run = run(c, InterrogationCommand.class);
		assertEquals(StandardCause.ACTIVATION_CONFIRM, cause(run.get(0)));
		assertEquals(StandardCause.ACTIVATION_TERMINATION, cause(run.get(run.size() - 1)));
		assertTrue(run.size() > 2, () -> "data between confirmation and termination: " + run);
		run.subList(1, run.size() - 1).forEach(m -> assertEquals(StandardCause.GROUP_REQUEST_10, cause(m)));
		// nothing of the interrogation after its termination
		await().atMost(WAIT).until(() -> c.values.size() == 1005);
		assertEquals(run.size() - 2, c.received.stream()
				.filter(m -> m instanceof AbstractMessage && cause(m) == StandardCause.GROUP_REQUEST_10).count());
	}

	// ------------------------------------------------------------------- helpers

	/** Connected, with the station interrogation done and the log cleared. */
	private TestClient started()
	{
		final TestClient c = connect(startServer(null), null);
		await().atMost(WAIT).until(() -> c.values.size() == 5);
		await().atMost(WAIT).until(() -> c.messages.stream().anyMatch(m -> m instanceof InterrogationCommand
				&& cause(m) == StandardCause.ACTIVATION_TERMINATION));
		c.received.clear();
		c.messages.clear();
		return c;
	}

	/** The messages from the confirmation of a {@code command} to its termination. */
	private static List<Object> run(final TestClient c, final Class<?> command)
	{
		await().atMost(WAIT).until(() -> c.received.stream()
				.anyMatch(m -> command.isInstance(m) && cause(m) == StandardCause.ACTIVATION_TERMINATION));
		final List<Object> all = new ArrayList<>(c.received);
		int start = 0;
		while (!(command.isInstance(all.get(start)) && cause(all.get(start)) == StandardCause.ACTIVATION_CONFIRM)) {
			start++;
		}
		int end = start;
		while (!(command.isInstance(all.get(end)) && cause(all.get(end)) == StandardCause.ACTIVATION_TERMINATION)) {
			end++;
		}
		return all.subList(start, end + 1);
	}

	private static List<IntegratedTotalsTimeSingle> totals(final List<Object> run)
	{
		return run.stream().filter(IntegratedTotalsTimeSingle.class::isInstance)
				.map(IntegratedTotalsTimeSingle.class::cast).toList();
	}

	private static Object cause(final Object message)
	{
		return ((AbstractMessage)message).getHeader().getCauseOfTransmission().getCause();
	}

	private static CounterInterrogationCommand counter(final int request, final int freeze)
	{
		return new CounterInterrogationCommand(header(), CounterInterrogationCommand.qualifier(request, freeze));
	}

	private static ASDUHeader header()
	{
		return new ASDUHeader(new CauseOfTransmission(StandardCause.ACTIVATED), TestStation.ADDRESS);
	}
}
