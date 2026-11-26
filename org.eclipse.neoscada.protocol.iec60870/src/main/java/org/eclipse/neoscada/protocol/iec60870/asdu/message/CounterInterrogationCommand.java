/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.asdu.message;

import org.eclipse.neoscada.protocol.iec60870.ProtocolOptions;
import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDU;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.Cause;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;

import io.netty.buffer.ByteBuf;

/**
 * C_CI_NA_1, the counter interrogation command. Its qualifier (QCC) is a request (RQT, the low six bits: a group 1 to
 * 4, or all counters) and what to do with the counters (FRZ, the top two bits: read, freeze, freeze and reset, or
 * reset).
 */
@ASDU(id = 101, name = "C_CI_NA_1")
public class CounterInterrogationCommand extends AbstractInformationObjectMessage {

	/** RQT: counter group 1; groups 2 to 4 follow it. */
	public static final byte	REQUEST_GROUP_1			= 1;

	/** RQT: all counters. */
	public static final byte	REQUEST_GENERAL			= 5;

	/** FRZ: read the counters (or their frozen values). */
	public static final byte	FREEZE_READ				= 0;

	/** FRZ: freeze without resetting. */
	public static final byte	FREEZE_NO_RESET			= 1;

	/** FRZ: freeze and reset. */
	public static final byte	FREEZE_WITH_RESET		= 2;

	/** FRZ: reset without freezing. */
	public static final byte	FREEZE_RESET			= 3;

	private static final int	MASK_REQUEST			= 0b00111111;

	private final byte			qualifier;

	public CounterInterrogationCommand(final ASDUHeader header, final byte qualifier)
	{
		this(header, InformationObjectAddress.DEFAULT, qualifier);
	}

	public CounterInterrogationCommand(final ASDUHeader header,
			final InformationObjectAddress informationObjectAddress, final byte qualifier)
	{
		super(header, informationObjectAddress);
		this.qualifier = qualifier;
	}

	/** A qualifier from its request (RQT) and freeze (FRZ) parts. */
	public static byte qualifier(final int request, final int freeze)
	{
		if (request < 0 || request > MASK_REQUEST || freeze < 0 || freeze > 3) {
			throw new IllegalArgumentException(String.format("RQT %s / FRZ %s", request, freeze));
		}
		return (byte)(freeze << 6 | request);
	}

	public byte getQualifier()
	{
		return this.qualifier;
	}

	/** RQT: 1 to 4 for a group, 5 for all counters. */
	public int getRequest()
	{
		return this.qualifier & MASK_REQUEST;
	}

	/** FRZ: one of the {@code FREEZE_} constants. */
	public int getFreeze()
	{
		return (this.qualifier & 0xFF) >> 6;
	}

	public static CounterInterrogationCommand parse(final ProtocolOptions options, final byte length,
			final ASDUHeader header, final ByteBuf data)
	{
		final InformationObjectAddress address = InformationObjectAddress.parse(options, data);
		return new CounterInterrogationCommand(header, address, data.readByte());
	}

	@Override
	public void encode(final ProtocolOptions options, final ByteBuf out)
	{
		EncodeHelper.encodeHeader(this, options, null, this.header, out);
		this.informationObjectAddress.encode(options, out);
		out.writeByte(this.qualifier);
	}

	public CounterInterrogationCommand mirror(final Cause cause)
	{
		return new CounterInterrogationCommand(this.header.clone(cause), this.informationObjectAddress,
				this.qualifier);
	}

	public CounterInterrogationCommand mirror(final Cause cause, final boolean positive)
	{
		return new CounterInterrogationCommand(this.header.clone(cause, positive), this.informationObjectAddress,
				this.qualifier);
	}
}
