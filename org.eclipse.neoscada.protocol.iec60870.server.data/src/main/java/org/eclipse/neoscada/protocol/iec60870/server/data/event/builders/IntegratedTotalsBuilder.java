/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.server.data.event.builders;

import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.IntegratedTotalsSequence;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.IntegratedTotalsSingle;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.IntegratedTotalsTimeSingle;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.BinaryCounterReading;
import org.eclipse.neoscada.protocol.iec60870.server.data.event.AbstractMessageBuilder;
import org.eclipse.neoscada.protocol.iec60870.server.data.event.MessageBuilder;
import org.eclipse.neoscada.protocol.iec60870.server.data.event.MessageBuilderFactory;

/**
 * Integrated totals, M_IT_NA_1 or M_IT_TB_1. Unlike the other types, a counter's time tag is kept whatever the cause:
 * a reading is usually the value at a freeze, and the time of the freeze is what it means.
 */
public class IntegratedTotalsBuilder implements MessageBuilderFactory<BinaryCounterReading> {

	private final boolean withTimestamps;

	public IntegratedTotalsBuilder(final boolean withTimestamps)
	{
		this.withTimestamps = withTimestamps;
	}

	@Override
	public MessageBuilder<BinaryCounterReading, ?> create()
	{
		final boolean withTimestamps = this.withTimestamps;
		return new AbstractMessageBuilder<BinaryCounterReading, Object>(BinaryCounterReading.class, 20, 20,
				withTimestamps ? 10 : -1) {

			@Override
			public boolean isWithTimestamps()
			{
				return withTimestamps;
			}

			@Override
			public Object build()
			{
				validateStart();
				final ASDUHeader header = new ASDUHeader(this.causeOfTransmission, this.asduAddress);
				if (withTimestamps) {
					return IntegratedTotalsTimeSingle.create(header, this.entries);
				}
				if (isContinuous()) {
					return IntegratedTotalsSequence.create(getStartAddress(), header, getValues());
				}
				return IntegratedTotalsSingle.create(header, this.entries);
			}
		};
	}
}
