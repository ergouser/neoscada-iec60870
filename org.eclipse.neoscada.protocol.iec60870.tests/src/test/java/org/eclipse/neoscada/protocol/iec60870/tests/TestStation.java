/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.CounterInterrogationCommand;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDUAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.BinaryCounterReading;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.CauseOfTransmission;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.CommandValue;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationEntry;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.QualityInformation;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.StandardCause;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.Value;
import org.eclipse.neoscada.protocol.iec60870.io.MirrorCommand;
import org.eclipse.neoscada.protocol.iec60870.server.data.AbstractBaseDataModel;
import org.eclipse.neoscada.protocol.iec60870.server.data.BackgroundIterator;
import org.eclipse.neoscada.protocol.iec60870.server.data.DataListener;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * An outstation with common address 1, a few measured values (short floats from IOA 100) and single points (from IOA
 * 200), held in memory, that accepts every command and records it.
 */
final class TestStation extends AbstractBaseDataModel {

	static final ASDUAddress ADDRESS = ASDUAddress.valueOf(1);

	/** A command the station received. */
	record Command(int ioa, Object value, byte type, boolean execute) {
	}

	private final Map<Integer, Float> floats = new TreeMap<>();

	private final Map<Integer, Boolean> singles = new TreeMap<>();

	/** Counters, each with its group: 300 in group 1, 301 in group 2. */
	private final Map<Integer, Integer> counters = new TreeMap<>(Map.of(300, 1000, 301, -5));

	private final Map<Integer, Integer> groups = Map.of(300, 1, 301, 2);

	/** The values at the last freeze, which a read returns. */
	private final Map<Integer, Integer> frozen = new TreeMap<>(this.counters);

	private int sequence;

	/** The time of the last freeze, the time tag of a frozen reading. */
	private long frozenAt = System.currentTimeMillis();

	final List<Command> commands = new CopyOnWriteArrayList<>();

	TestStation()
	{
		super("TestStation");
		this.floats.put(100, 1.5f);
		this.floats.put(101, -230.25f);
		this.floats.put(102, 0f);
		this.singles.put(200, true);
		this.singles.put(201, false);
	}

	/** Changes a measured value and reports it spontaneously. */
	synchronized void set(final int ioa, final float value)
	{
		this.floats.put(ioa, value);
		notifyChangeShortFloatingPoint(CauseOfTransmission.SPONTANEOUS, ADDRESS,
				List.of(new InformationEntry<>(InformationObjectAddress.valueOf(ioa), Value.ok(value))), false);
	}

	@Override
	public ListenableFuture<Value<?>> read(final ASDUAddress asduAddress, final InformationObjectAddress address)
	{
		if (!ADDRESS.equals(asduAddress)) {
			return null;
		}
		synchronized (this) {
			final Float f = this.floats.get(address.getAddress());
			if (f != null) {
				return Futures.immediateFuture(Value.ok(f));
			}
			final Boolean b = this.singles.get(address.getAddress());
			return b != null ? Futures.immediateFuture(Value.ok(b)) : null;
		}
	}

	@Override
	public ListenableFuture<Void> readAll(final CauseOfTransmission cause, final ASDUAddress asduAddress,
			final Runnable prepare, final DataListener listener)
	{
		if (!ADDRESS.equals(asduAddress)) {
			return null;
		}
		return this.executor.submit(() -> {
			prepare.run();
			final List<InformationEntry<Float>> f = new ArrayList<>();
			final List<InformationEntry<Boolean>> b = new ArrayList<>();
			synchronized (this) {
				this.floats.forEach((k, v) -> f.add(new InformationEntry<>(InformationObjectAddress.valueOf(k),
						Value.ok(v))));
				this.singles.forEach((k, v) -> b.add(new InformationEntry<>(InformationObjectAddress.valueOf(k),
						Value.ok(v))));
			}
			listener.dataChangeShortFloatingPoint(cause, ADDRESS, f, false);
			listener.dataChangeSinglePoint(cause, ADDRESS, b, false);
			return null;
		});
	}

	/** Adds {@code n} measured values at every other address from 1000, more than one window of frames holds. */
	synchronized void addFloats(final int n)
	{
		for (int i = 0; i < n; i++) {
			this.floats.put(1000 + 2 * i, (float)i);
		}
	}

	synchronized void count(final int ioa, final int increment)
	{
		this.counters.merge(ioa, increment, Integer::sum);
	}

	/**
	 * Counters in the way most meters keep them (mode D of IEC 60870-5-101 7.4.8): a freeze copies them, a read
	 * returns the copy, time tagged with the freeze.
	 */
	@Override
	public ListenableFuture<Void> readCounters(final CauseOfTransmission cause, final ASDUAddress asduAddress,
			final int freeze, final Runnable prepare, final DataListener listener)
	{
		if (!ADDRESS.equals(asduAddress)) {
			return null;
		}
		final int group = cause.getCause().getValue() - StandardCause.COUNTER_GENERAL.getValue();
		return this.executor.submit(() -> {
			prepare.run();
			final List<InformationEntry<BinaryCounterReading>> readings = new ArrayList<>();
			synchronized (this) {
				for (final Map.Entry<Integer, Integer> e : this.counters.entrySet()) {
					if (group != 0 && this.groups.get(e.getKey()) != group) {
						continue;
					}
					switch (freeze) {
						case CounterInterrogationCommand.FREEZE_READ -> readings.add(new InformationEntry<>(
								InformationObjectAddress.valueOf(e.getKey()), new Value<>(new BinaryCounterReading(
										this.frozen.get(e.getKey()), this.sequence, false, false), this.frozenAt,
										QualityInformation.OK)));
						case CounterInterrogationCommand.FREEZE_NO_RESET -> this.frozen.put(e.getKey(), e.getValue());
						case CounterInterrogationCommand.FREEZE_WITH_RESET -> {
							this.frozen.put(e.getKey(), e.getValue());
							e.setValue(0);
						}
						default -> e.setValue(0);
					}
				}
				if (freeze == CounterInterrogationCommand.FREEZE_NO_RESET
						|| freeze == CounterInterrogationCommand.FREEZE_WITH_RESET) {
					this.sequence = (this.sequence + 1) % 32;
					this.frozenAt = System.currentTimeMillis();
				}
			}
			if (!readings.isEmpty()) {
				listener.dataChangeIntegratedTotals(cause, ADDRESS, readings, true);
			}
			return null;
		});
	}

	@Override
	public BackgroundIterator createBackgroundIterator()
	{
		return () -> null;
	}

	@Override
	public void forAllAsdu(final Consumer<ASDUAddress> function, final Runnable ifNoneFound)
	{
		function.accept(ADDRESS);
	}

	@Override
	public void writeValue(final ASDUHeader header, final InformationObjectAddress informationObjectAddress,
			final CommandValue<?> value, final byte type, final MirrorCommand mirrorCommand, final boolean execute)
	{
		this.commands.add(new Command(informationObjectAddress.getAddress(), value.getValue(), type, execute));
		mirrorCommand.sendActivationConfirm(true);
		if (execute) {
			mirrorCommand.sendActivationTermination();
		}
	}
}
