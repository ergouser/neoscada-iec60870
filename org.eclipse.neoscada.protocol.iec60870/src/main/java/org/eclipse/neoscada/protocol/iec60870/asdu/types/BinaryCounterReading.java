/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.asdu.types;

import io.netty.buffer.ByteBuf;

/**
 * A binary counter reading (BCR, IEC 60870-5-101 7.2.6.9): a signed 32 bit count with its sequence number and the
 * carry and adjusted flags. The invalid flag (IV) travels as the {@link QualityInformation} of the {@link Value} that
 * holds the reading.
 */
public final class BinaryCounterReading {

	private static final int	MASK_SEQUENCE	= 0b00011111;

	private static final int	MASK_CARRY		= 0b00100000;

	private static final int	MASK_ADJUSTED	= 0b01000000;

	private static final int	MASK_INVALID	= 0b10000000;

	private final int			value;

	private final int			sequence;

	private final boolean		carry;

	private final boolean		adjusted;

	/**
	 * @param sequence
	 *            the sequence notation, 0 to 31, counting the freezes
	 */
	public BinaryCounterReading(final int value, final int sequence, final boolean carry, final boolean adjusted)
	{
		if (sequence < 0 || sequence > MASK_SEQUENCE) {
			throw new IllegalArgumentException("sequence must be 0 to 31: " + sequence);
		}
		this.value = value;
		this.sequence = sequence;
		this.carry = carry;
		this.adjusted = adjusted;
	}

	public BinaryCounterReading(final int value)
	{
		this(value, 0, false, false);
	}

	public int getValue()
	{
		return this.value;
	}

	public int getSequence()
	{
		return this.sequence;
	}

	/** The counter overflowed during the period this reading closes. */
	public boolean isCarry()
	{
		return this.carry;
	}

	/** The counter was adjusted during the period this reading closes. */
	public boolean isAdjusted()
	{
		return this.adjusted;
	}

	public static void encode(final ByteBuf out, final Value<BinaryCounterReading> value)
	{
		final BinaryCounterReading reading = value.getValue();
		out.writeIntLE(reading.value);
		int flags = reading.sequence;
		if (reading.carry) {
			flags |= MASK_CARRY;
		}
		if (reading.adjusted) {
			flags |= MASK_ADJUSTED;
		}
		if (!value.getQualityInformation().isValid()) {
			flags |= MASK_INVALID;
		}
		out.writeByte(flags);
	}

	/** Reads the five octets of a reading; the caller adds the time tag. */
	public static Value<BinaryCounterReading> parse(final ByteBuf data, final long timestamp)
	{
		final int value = data.readIntLE();
		final int flags = data.readUnsignedByte();
		final BinaryCounterReading reading = new BinaryCounterReading(value, flags & MASK_SEQUENCE,
				(flags & MASK_CARRY) != 0, (flags & MASK_ADJUSTED) != 0);
		return new Value<>(reading, timestamp, (flags & MASK_INVALID) != 0 ? QualityInformation.INVALID
				: QualityInformation.OK);
	}

	@Override
	public String toString()
	{
		return String.format("%s[SQ=%s%s%s]", this.value, this.sequence, this.carry ? " CY" : "",
				this.adjusted ? " CA" : "");
	}

	@Override
	public int hashCode()
	{
		return ((this.value * 31 + this.sequence) * 31 + (this.carry ? 1 : 0)) * 31 + (this.adjusted ? 1 : 0);
	}

	@Override
	public boolean equals(final Object obj)
	{
		if (!(obj instanceof BinaryCounterReading)) {
			return false;
		}
		final BinaryCounterReading other = (BinaryCounterReading)obj;
		return this.value == other.value && this.sequence == other.sequence && this.carry == other.carry
				&& this.adjusted == other.adjusted;
	}
}
