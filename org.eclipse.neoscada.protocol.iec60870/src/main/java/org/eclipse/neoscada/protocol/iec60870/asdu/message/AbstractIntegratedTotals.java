/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.asdu.message;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.neoscada.protocol.iec60870.ProtocolOptions;
import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.BinaryCounterReading;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationEntry;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.TypeHelper;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.Value;

import io.netty.buffer.ByteBuf;

/** Integrated totals: counter readings, each at its own address. */
public abstract class AbstractIntegratedTotals extends AbstractMessage {

	protected final List<InformationEntry<BinaryCounterReading>>	entries;

	private final boolean											withTimestamp;

	protected AbstractIntegratedTotals(final ASDUHeader header,
			final List<InformationEntry<BinaryCounterReading>> entries, final boolean withTimestamp)
	{
		super(header);
		if (entries.size() > MAX_INFORMATION_ENTRIES) {
			throw new IllegalArgumentException(
					String.format("A maximum of %s values can be transmitted", MAX_INFORMATION_ENTRIES));
		}
		this.entries = List.copyOf(entries);
		this.withTimestamp = withTimestamp;
	}

	public List<InformationEntry<BinaryCounterReading>> getEntries()
	{
		return this.entries;
	}

	@Override
	public void encode(final ProtocolOptions options, final ByteBuf out)
	{
		EncodeHelper.encodeHeader(this, options, this.entries.size(), this.header, out);
		for (final InformationEntry<BinaryCounterReading> entry : this.entries) {
			entry.getAddress().encode(options, out);
			BinaryCounterReading.encode(out, entry.getValue());
			if (this.withTimestamp) {
				TypeHelper.encodeTimestamp(options, out, entry.getValue().getTimestamp());
			}
		}
	}

	protected static List<InformationEntry<BinaryCounterReading>> parseEntries(final ProtocolOptions options,
			final byte length, final ByteBuf data, final boolean withTimestamp)
	{
		final List<InformationEntry<BinaryCounterReading>> values = new ArrayList<>(length);
		for (int i = 0; i < length; i++) {
			final InformationObjectAddress address = InformationObjectAddress.parse(options, data);
			Value<BinaryCounterReading> value = BinaryCounterReading.parse(data, System.currentTimeMillis());
			if (withTimestamp) {
				value = new Value<>(value.getValue(), TypeHelper.parseTimestamp(options, data),
						value.getQualityInformation());
			}
			values.add(new InformationEntry<>(address, value));
		}
		return values;
	}
}
