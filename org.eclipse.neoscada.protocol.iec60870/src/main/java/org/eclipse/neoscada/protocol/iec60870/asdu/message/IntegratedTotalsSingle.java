/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.asdu.message;

import java.util.List;

import org.eclipse.neoscada.protocol.iec60870.ProtocolOptions;
import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDU;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.BinaryCounterReading;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationEntry;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationStructure;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.Value;

import io.netty.buffer.ByteBuf;

@ASDU(id = 15, name = "M_IT_NA_1", informationStructure = InformationStructure.SINGLE)
public class IntegratedTotalsSingle extends AbstractIntegratedTotals {

	private IntegratedTotalsSingle(final ASDUHeader header, final List<InformationEntry<BinaryCounterReading>> entries)
	{
		super(header, entries, false);
	}

	public static IntegratedTotalsSingle parse(final ProtocolOptions options, final byte length, final ASDUHeader header,
			final ByteBuf data)
	{
		return new IntegratedTotalsSingle(header, parseEntries(options, length, data, false));
	}

	public static IntegratedTotalsSingle create(final ASDUHeader header, final InformationObjectAddress address,
			final Value<BinaryCounterReading> value)
	{
		return new IntegratedTotalsSingle(header, List.of(new InformationEntry<>(address, value)));
	}

	public static IntegratedTotalsSingle create(final ASDUHeader header,
			final List<InformationEntry<BinaryCounterReading>> values)
	{
		return new IntegratedTotalsSingle(header, values);
	}
}
