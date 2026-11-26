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
import org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDU;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.BinaryCounterReading;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationStructure;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.Value;

import io.netty.buffer.ByteBuf;

/** M_IT_NA_1 as a sequence (SQ=1): counters at consecutive addresses from a start address. */
@ASDU(id = 15, name = "M_IT_NA_1", informationStructure = InformationStructure.SEQUENCE)
public class IntegratedTotalsSequence extends AbstractMessage {

	private final InformationObjectAddress			startAddress;

	private final List<Value<BinaryCounterReading>>	values;

	private IntegratedTotalsSequence(final ASDUHeader header, final InformationObjectAddress startAddress,
			final List<Value<BinaryCounterReading>> values)
	{
		super(header);
		if (values.size() > MAX_INFORMATION_ENTRIES) {
			throw new IllegalArgumentException(
					String.format("A maximum of %s values can be transmitted", MAX_INFORMATION_ENTRIES));
		}
		this.startAddress = startAddress;
		this.values = List.copyOf(values);
	}

	public InformationObjectAddress getStartAddress()
	{
		return this.startAddress;
	}

	public List<Value<BinaryCounterReading>> getValues()
	{
		return this.values;
	}

	public static IntegratedTotalsSequence parse(final ProtocolOptions options, final byte length,
			final ASDUHeader header, final ByteBuf data)
	{
		final InformationObjectAddress startAddress = InformationObjectAddress.parse(options, data);
		final List<Value<BinaryCounterReading>> values = new ArrayList<>(length);
		final long now = System.currentTimeMillis();
		for (int i = 0; i < length; i++) {
			values.add(BinaryCounterReading.parse(data, now));
		}
		return new IntegratedTotalsSequence(header, startAddress, values);
	}

	public static IntegratedTotalsSequence create(final InformationObjectAddress startAddress,
			final ASDUHeader header, final List<Value<BinaryCounterReading>> values)
	{
		return new IntegratedTotalsSequence(header, startAddress, values);
	}

	@Override
	public void encode(final ProtocolOptions options, final ByteBuf out)
	{
		EncodeHelper.encodeHeader(this, options, this.values.size(), this.header, out);
		this.startAddress.encode(options, out);
		for (final Value<BinaryCounterReading> value : this.values) {
			BinaryCounterReading.encode(out, value);
		}
	}
}
