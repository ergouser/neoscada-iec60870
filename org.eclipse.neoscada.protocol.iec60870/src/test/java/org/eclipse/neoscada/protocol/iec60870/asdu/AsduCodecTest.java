/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.asdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.neoscada.protocol.iec60870.ProtocolOptions;
import org.eclipse.neoscada.protocol.iec60870.apci.InformationTransfer;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.*;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.StandardCause;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * Every ASDU type the stack registers: a frame written out byte by byte from the layout in IEC 60870-5-101/104
 * (default sizes: two-octet cause of transmission and common address, three-octet object address), decoded to the
 * expected class and values, and encoded back to the same bytes.
 */
class AsduCodecTest {

	/** Common address 1. */
	private static final String CA = "01 00 ";

	/** Object address 100. */
	private static final String IOA = "64 00 00 ";

	/** Spontaneous, originator 0. */
	private static final String SPONT = "03 00 ";

	/** Activation, originator 0. */
	private static final String ACT = "06 00 ";

	/** CP56Time2a for 2026-11-25 13:45:30.250 UTC, day of week not given. */
	private static final String TIME = "2A 76 2D 0D 19 0B 1A";

	private static final long TIME_MILLIS = Instant.parse("2026-11-25T13:45:30.250Z").toEpochMilli();

	private static final ProtocolOptions OPTIONS = new ProtocolOptions.Builder().build();

	static Stream<Arguments> frames()
	{
		return Stream.of(
				// monitor direction
				frame("M_SP_NA_1", "01 01 " + SPONT + CA + IOA + "01", SinglePointInformationSingle.class,
						(final SinglePointInformationSingle m) -> {
							assertEquals(100, m.getEntries().get(0).getAddress().getAddress());
							assertEquals(Boolean.TRUE, m.getEntries().get(0).getValue().getValue());
						}),
				frame("M_SP_NA_1 sequence", "01 82 14 00 " + CA + IOA + "01 10", SinglePointInformationSequence.class,
						(final SinglePointInformationSequence m) -> {
							assertEquals(StandardCause.STATION_REQUEST,
									m.getHeader().getCauseOfTransmission().getCause());
							assertEquals(2, m.getValues().size());
							assertEquals(Boolean.FALSE, m.getValues().get(1).getValue());
							assertTrue(m.getValues().get(1).getQualityInformation().isBlocked());
						}),
				frame("M_DP_NA_1", "03 01 " + SPONT + CA + IOA + "02", DoublePointInformationSingle.class, null),
				frame("M_DP_NA_1 sequence", "03 82 " + SPONT + CA + IOA + "01 82", DoublePointInformationSequence.class,
						null),
				frame("M_BO_NA_1", "07 01 " + SPONT + CA + IOA + "78 56 34 12 00", BitstringSingle.class, null),
				frame("M_ME_NA_1", "09 01 " + SPONT + CA + IOA + "00 40 00", MeasuredValueNormalizedSingle.class,
						(final MeasuredValueNormalizedSingle m) -> assertEquals(0.5,
								m.getEntries().get(0).getValue().getValue(), 1e-9)),
				frame("M_ME_NA_1 sequence", "09 82 " + SPONT + CA + IOA + "00 40 00 00 C0 00",
						MeasuredValueNormalizedSequence.class,
						(final MeasuredValueNormalizedSequence m) -> assertEquals(-0.5,
								m.getValues().get(1).getValue(), 1e-9)),
				frame("M_ME_NB_1", "0B 01 " + SPONT + CA + IOA + "D2 04 00", MeasuredValueScaledSingle.class,
						(final MeasuredValueScaledSingle m) -> assertEquals((short)1234,
								m.getEntries().get(0).getValue().getValue())),
				frame("M_ME_NB_1 sequence", "0B 82 " + SPONT + CA + IOA + "D2 04 00 2E FB 00",
						MeasuredValueScaledSequence.class,
						(final MeasuredValueScaledSequence m) -> assertEquals((short)-1234,
								m.getValues().get(1).getValue())),
				frame("M_ME_NC_1", "0D 01 " + SPONT + CA + IOA + "00 00 C0 3F 00",
						MeasuredValueShortFloatingPointSingle.class,
						(final MeasuredValueShortFloatingPointSingle m) -> assertEquals(1.5f,
								m.getEntries().get(0).getValue().getValue())),
				frame("M_ME_NC_1 sequence", "0D 82 " + SPONT + CA + IOA + "00 00 C0 3F 00 00 00 20 C1 01",
						MeasuredValueShortFloatingPointSequence.class,
						(final MeasuredValueShortFloatingPointSequence m) -> {
							assertEquals(-10.0f, m.getValues().get(1).getValue());
							assertTrue(m.getValues().get(1).isOverflow());
						}),
				frame("M_SP_TB_1", "1E 01 " + SPONT + CA + IOA + "01 " + TIME, SinglePointInformationTimeSingle.class,
						(final SinglePointInformationTimeSingle m) -> assertEquals(TIME_MILLIS,
								m.getEntries().get(0).getValue().getTimestamp())),
				frame("M_DP_TB_1", "1F 01 " + SPONT + CA + IOA + "02 " + TIME, DoublePointInformationTimeSingle.class,
						null),
				frame("M_BO_TB_1", "21 01 " + SPONT + CA + IOA + "78 56 34 12 00 " + TIME, BitstringTimeSingle.class,
						null),
				frame("M_ME_TD_1", "22 01 " + SPONT + CA + IOA + "00 40 00 " + TIME,
						MeasuredValueNormalizedTimeSingle.class, null),
				frame("M_ME_TE_1", "23 01 " + SPONT + CA + IOA + "D2 04 00 " + TIME, MeasuredValueScaledTimeSingle.class,
						null),
				frame("M_ME_TF_1", "24 01 " + SPONT + CA + IOA + "00 00 C0 3F 00 " + TIME,
						MeasuredValueShortFloatingPointTimeSingle.class,
						(final MeasuredValueShortFloatingPointTimeSingle m) -> {
							final Value<Float> v = m.getEntries().get(0).getValue();
							assertEquals(1.5f, v.getValue());
							assertEquals(TIME_MILLIS, v.getTimestamp());
						}),
				frame("M_EI_NA_1", "46 01 04 00 " + CA + "00 00 00 00", EndOfInitialization.class, null),

				// control direction
				frame("C_SC_NA_1", "2D 01 " + ACT + CA + IOA + "01", SingleCommand.class, (final SingleCommand m) -> {
					assertTrue(m.getState());
					assertTrue(m.isExecute());
				}),
				frame("C_SC_NA_1 select", "2D 01 " + ACT + CA + IOA + "81", SingleCommand.class,
						(final SingleCommand m) -> assertTrue(!m.isExecute())),
				frame("C_DC_NA_1", "2E 01 " + ACT + CA + IOA + "02", DoubleCommand.class, null),
				frame("C_SE_NA_1", "30 01 " + ACT + CA + IOA + "00 40 00", SetPointCommandNormalizedValue.class, null),
				frame("C_SE_NB_1", "31 01 " + ACT + CA + IOA + "D2 04 00", SetPointCommandScaledValue.class, null),
				frame("C_SE_NC_1", "32 01 " + ACT + CA + IOA + "00 00 C0 3F 00", SetPointCommandShortFloatingPoint.class,
						(final SetPointCommandShortFloatingPoint m) -> assertEquals(1.5f, m.getValue().getValue())),
				frame("C_SC_TA_1", "3A 01 " + ACT + CA + IOA + "01 " + TIME, SingleCommandTime.class, null),
				frame("C_DC_TA_1", "3B 01 " + ACT + CA + IOA + "02 " + TIME, DoubleCommandTime.class, null),
				frame("C_SE_TA_1", "3D 01 " + ACT + CA + IOA + "00 40 00 " + TIME,
						SetPointCommandNormalizedValueTime.class, null),
				frame("C_SE_TB_1", "3E 01 " + ACT + CA + IOA + "D2 04 00 " + TIME, SetPointCommandScaledValueTime.class,
						null),
				frame("C_SE_TC_1", "3F 01 " + ACT + CA + IOA + "00 00 C0 3F 00 " + TIME,
						SetPointCommandShortFloatingPointTime.class, null),
				frame("C_IC_NA_1", "64 01 " + ACT + CA + "00 00 00 14", InterrogationCommand.class,
						(final InterrogationCommand m) -> assertEquals(20, m.getQualifierOfInterrogation())),
				frame("C_RD_NA_1", "66 01 05 00 " + CA + IOA.trim(), ReadCommand.class, null));
	}

	private static <T> Arguments frame(final String name, final String hex, final Class<T> type,
			final Consumer<T> check)
	{
		return Arguments.of(name, hex, type, check);
	}

	static MessageManager manager()
	{
		final MessageManager manager = new MessageManager(AsduCodecTest.OPTIONS);
		new MessageRegistrator().register(manager);
		return manager;
	}

	static Object decode(final MessageManager manager, final String hex)
	{
		final List<Object> out = new ArrayList<>();
		final ByteBuf data = Unpooled.wrappedBuffer(HexFormat.ofDelimiter(" ").parseHex(hex));
		final ByteBuf error = manager.receiveMessage(new InformationTransfer(0, 0, data), out);
		assertNull(error, () -> "refused as " + ByteBufUtil.hexDump(error));
		assertEquals(1, out.size());
		assertEquals(0, data.readableBytes(), "bytes left over after decoding");
		return out.get(0);
	}

	static String encode(final MessageManager manager, final Object message)
	{
		final ByteBuf out = Unpooled.buffer();
		manager.encodeMessage(message, out);
		return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(ByteBufUtil.getBytes(out));
	}

	@SuppressWarnings("unchecked")
	@ParameterizedTest(name = "{0}")
	@MethodSource("frames")
	<T> void decodesAndEncodesBack(final String name, final String hex, final Class<T> type, final Consumer<T> check)
	{
		final MessageManager manager = AsduCodecTest.manager();
		final Object message = AsduCodecTest.decode(manager, hex);
		assertEquals(type, message.getClass());
		if (check != null) {
			check.accept((T)message);
		}
		assertEquals(hex.toUpperCase(), AsduCodecTest.encode(manager, message));
	}

	@Test
	void anUnknownTypeIsMirroredWithCause44()
	{
		final MessageManager manager = AsduCodecTest.manager();
		// M_IT_NA_1 (integrated totals) is not one of the registered types
		final ByteBuf data = Unpooled
				.wrappedBuffer(HexFormat.ofDelimiter(" ").parseHex("0F 01 " + SPONT + CA + IOA + "01 00 00 00 00"));
		final List<Object> out = new ArrayList<>();
		final ByteBuf reply = manager.receiveMessage(new InformationTransfer(0, 0, data), out);
		assertTrue(out.isEmpty());
		assertEquals("0F 01 2C 00 01 00 64 00 00 01 00 00 00 00",
				HexFormat.ofDelimiter(" ").withUpperCase().formatHex(ByteBufUtil.getBytes(reply)));
	}
}
