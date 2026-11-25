/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.apci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.neoscada.protocol.iec60870.ProtocolOptions;
import org.eclipse.neoscada.protocol.iec60870.asdu.ASDUHeader;
import org.eclipse.neoscada.protocol.iec60870.asdu.MessageManager;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.DataTransmissionMessage;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.InterrogationCommand;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.MessageRegistrator;
import org.eclipse.neoscada.protocol.iec60870.asdu.message.SinglePointInformationSingle;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDUAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.CauseOfTransmission;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.StandardCause;
import org.eclipse.neoscada.protocol.iec60870.apci.UnnumberedControl.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * The APCI layer of IEC 60870-5-104 (clause 5): framing, the k and w windows, and timers t1, t2 and t3, driven on an
 * {@link EmbeddedChannel} whose clock the test moves.
 */
class LinkLayerTest {

	private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

	/** k = 3, w = 2, t1 = 15 s, t2 = 10 s, t3 = 20 s. */
	private ProtocolOptions options;

	private EmbeddedChannel channel;

	private MessageChannel messageChannel;

	@BeforeEach
	void setUp()
	{
		final ProtocolOptions.Builder builder = new ProtocolOptions.Builder();
		builder.setMaxUnacknowledged((short)3);
		builder.setAcknowledgeWindow((short)2);
		this.options = builder.build();
		final MessageManager manager = new MessageManager(this.options);
		new MessageRegistrator().register(manager);
		this.messageChannel = new MessageChannel(this.options, manager);
		this.channel = new EmbeddedChannel(new APDUDecoder(), new APDUEncoder(), this.messageChannel,
				LinkLayerTest.closeOnError());
		this.channel.freezeTime();
	}

	// ------------------------------------------------------------------- framing

	@Test
	void decodesEachFrameFormat()
	{
		final EmbeddedChannel decoder = new EmbeddedChannel(new APDUDecoder());
		decoder.writeInbound(LinkLayerTest.bytes("68 04 07 00 00 00 68 04 83 00 00 00 68 04 01 00 0A 00"));
		assertEquals(Function.STARTDT_ACT, ((UnnumberedControl)decoder.readInbound()).getFunction());
		assertEquals(Function.TESTFR_CONFIRM, ((UnnumberedControl)decoder.readInbound()).getFunction());
		assertEquals(5, ((Supervisory)decoder.readInbound()).getReceiveSequenceNumber());

		decoder.writeInbound(LinkLayerTest.bytes("68 0E 06 00 0A 00 01 01 03 00"));
		assertNull(decoder.readInbound(), "half a frame is not a frame");
		decoder.writeInbound(LinkLayerTest.bytes("01 00 64 00 00 01"));
		final InformationTransfer i = decoder.readInbound();
		assertEquals(3, i.getSendSequenceNumber());
		assertEquals(5, i.getReceiveSequenceNumber());
		assertEquals("01 01 03 00 01 00 64 00 00 01", LinkLayerTest.HEX.formatHex(ByteBufUtil.getBytes(i.getData())));
	}

	@Test
	void encodesEachFrameFormat()
	{
		final EmbeddedChannel encoder = new EmbeddedChannel(new APDUEncoder());
		encoder.writeOutbound(new UnnumberedControl(Function.STARTDT_CONFIRM), new Supervisory(5),
				new InformationTransfer(3, 5, LinkLayerTest.bytes("01 01 03 00 01 00 64 00 00 01")));
		assertEquals("68 04 0B 00 00 00", LinkLayerTest.next(encoder));
		assertEquals("68 04 01 00 0A 00", LinkLayerTest.next(encoder));
		assertEquals("68 0E 06 00 0A 00 01 01 03 00 01 00 64 00 00 01", LinkLayerTest.next(encoder));
	}

	@Test
	void refusesAFrameThatDoesNotStartWith68()
	{
		final EmbeddedChannel decoder = new EmbeddedChannel(new APDUDecoder(), closeOnError());
		decoder.writeInbound(LinkLayerTest.bytes("67 04 07 00 00 00"));
		assertFalse(decoder.isOpen());
	}

	@Test
	void refusesALengthTooShortForTheControlFields()
	{
		final EmbeddedChannel decoder = new EmbeddedChannel(new APDUDecoder(), closeOnError());
		decoder.writeInbound(LinkLayerTest.bytes("68 02 07 00 68 04 07 00 00 00"));
		assertFalse(decoder.isOpen());
	}

	@Test
	void refusesALengthOver253()
	{
		final EmbeddedChannel decoder = new EmbeddedChannel(new APDUDecoder(), closeOnError());
		decoder.writeInbound(LinkLayerTest.bytes("68 FE 00 00 00 00"));
		assertFalse(decoder.isOpen());
	}

	// ------------------------------------------------------------------- U frames

	@Test
	void answersATestFrame()
	{
		this.channel.writeInbound(LinkLayerTest.bytes("68 04 43 00 00 00"));
		assertEquals("68 04 83 00 00 00", next());
	}

	@Test
	void sendsATestFrameWhenTheLinkIsIdleForT3()
	{
		this.messageChannel.startIdleTimer();
		advance(this.options.getTimeout3() - 1);
		assertNull(this.channel.readOutbound());
		advance(1);
		assertEquals("68 04 43 00 00 00", next());
	}

	@Test
	void closesWhenATestFrameIsNotConfirmedWithinT1()
	{
		this.messageChannel.startIdleTimer();
		advance(this.options.getTimeout3());
		assertEquals("68 04 43 00 00 00", next());
		advance(this.options.getTimeout1() - 1);
		assertTrue(this.channel.isOpen());
		advance(1);
		assertFalse(this.channel.isOpen());
	}

	@Test
	void closesWhenStartDtIsNotConfirmedWithinT1()
	{
		this.channel.writeOutbound(DataTransmissionMessage.REQUEST_START);
		assertEquals("68 04 07 00 00 00", next());
		advance(this.options.getTimeout1());
		assertFalse(this.channel.isOpen());
	}

	@Test
	void staysOpenWhenStartDtIsConfirmed()
	{
		this.channel.writeOutbound(DataTransmissionMessage.REQUEST_START);
		assertEquals("68 04 07 00 00 00", next());
		this.channel.writeInbound(LinkLayerTest.bytes("68 04 0B 00 00 00"));
		advance(this.options.getTimeout1());
		assertTrue(this.channel.isOpen());
	}

	@Test
	void anIdleLinkWithNothingOutstandingIsTestedNotClosed()
	{
		this.messageChannel.startTimers(); // as an outstation does on connect
		this.channel.writeInbound(LinkLayerTest.bytes("68 04 07 00 00 00")); // STARTDT act from the station
		advance(this.options.getTimeout1() + 1);
		assertTrue(this.channel.isOpen(), "t1 supervises only what was sent");
		advance(this.options.getTimeout3());
		assertEquals("68 04 43 00 00 00", next(), "t3 tests the idle link");
	}

	// ------------------------------------------------------------------- receiving: w and t2

	@Test
	void acknowledgesAfterWFramesReceived()
	{
		this.channel.writeInbound(iFrame(0, 0));
		assertNull(this.channel.readOutbound(), "one frame of w = 2 is not acknowledged at once");
		this.channel.writeInbound(iFrame(1, 0));
		assertEquals("68 04 01 00 04 00", next(), "S frame acknowledging both");
	}

	@Test
	void acknowledgesWithinT2()
	{
		this.channel.writeInbound(iFrame(0, 0));
		advance(this.options.getTimeout2() - 1);
		assertNull(this.channel.readOutbound());
		advance(1);
		assertEquals("68 04 01 00 02 00", next());
	}

	@Test
	void closesOnAnOutOfSequenceFrame()
	{
		this.channel.writeInbound(iFrame(0, 0));
		this.channel.writeInbound(iFrame(2, 0));
		assertFalse(this.channel.isOpen());
	}

	@Test
	void passesTheDecodedMessageOn()
	{
		this.channel.writeInbound(iFrame(0, 0));
		final Object message = this.channel.readInbound();
		assertTrue(message instanceof SinglePointInformationSingle, () -> String.valueOf(message));
	}

	// ------------------------------------------------------------------- sending: k and t1

	@Test
	void stopsSendingAtKUnacknowledgedFrames()
	{
		for (int i = 0; i < 5; i++) {
			this.channel.writeOutbound(interrogation());
		}
		final List<String> sent = drain();
		assertEquals(3, sent.size(), () -> "k = 3, but sent " + sent);
		assertTrue(sent.get(0).startsWith("68 0E 00 00 00 00"));
		assertTrue(sent.get(2).startsWith("68 0E 04 00 00 00"));

		this.channel.writeInbound(LinkLayerTest.bytes("68 04 01 00 04 00")); // ack of 0 and 1
		final List<String> more = drain();
		assertEquals(2, more.size(), () -> "two acknowledged, so two more, but sent " + more);
		assertTrue(more.get(0).startsWith("68 0E 06 00 00 00"));
	}

	@Test
	void closesWhenAnInformationFrameIsNotAcknowledgedWithinT1()
	{
		this.channel.writeOutbound(interrogation());
		assertEquals(1, drain().size());
		advance(this.options.getTimeout1() - 1);
		assertTrue(this.channel.isOpen());
		advance(1);
		assertFalse(this.channel.isOpen());
	}

	@Test
	void staysOpenWhenInformationFramesAreAcknowledged()
	{
		this.channel.writeOutbound(interrogation());
		drain();
		advance(this.options.getTimeout1() - 1);
		this.channel.writeInbound(LinkLayerTest.bytes("68 04 01 00 02 00"));
		advance(this.options.getTimeout1());
		assertTrue(this.channel.isOpen());
	}

	@Test
	void closesOnAnAcknowledgementOfAFrameNeverSent()
	{
		this.channel.writeOutbound(interrogation());
		drain();
		this.channel.writeInbound(LinkLayerTest.bytes("68 04 01 00 08 00")); // ack of 0..3, only 0 was sent
		assertFalse(this.channel.isOpen());
	}

	@Test
	void sequenceNumbersWrapAt32767()
	{
		final ProtocolOptions.Builder builder = new ProtocolOptions.Builder();
		builder.setMaxUnacknowledged((short)12);
		builder.setAcknowledgeWindow((short)8);
		final MessageManager manager = new MessageManager(builder.build());
		new MessageRegistrator().register(manager);
		final EmbeddedChannel c = new EmbeddedChannel(new APDUDecoder(), new APDUEncoder(),
				new MessageChannel(builder.build(), manager), closeOnError());
		int ns = 0;
		for (int n = 0; n < 32768 + 4; n++) {
			c.writeInbound(LinkLayerTest.iFrameBytes(ns, 0));
			ns = (ns + 1) & 0x7FFF;
			c.readInbound();
			ByteBuf out;
			while ((out = c.readOutbound()) != null) {
				out.release();
			}
		}
		assertTrue(c.isOpen(), "the receive sequence number wraps from 32767 to 0");
	}

	// ------------------------------------------------------------------- helpers

	private static ChannelInboundHandlerAdapter closeOnError()
	{
		return new ChannelInboundHandlerAdapter() {

			@Override
			public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
			{
				ctx.close();
			}
		};
	}

	private static InterrogationCommand interrogation()
	{
		return new InterrogationCommand(
				new ASDUHeader(new CauseOfTransmission(StandardCause.ACTIVATED), ASDUAddress.valueOf(1)), (short)20);
	}

	private ByteBuf iFrame(final int ns, final int nr)
	{
		return LinkLayerTest.iFrameBytes(ns, nr);
	}

	private static ByteBuf iFrameBytes(final int ns, final int nr)
	{
		final ByteBuf b = Unpooled.buffer();
		b.writeByte(0x68).writeByte(14).writeShortLE(ns << 1).writeShortLE(nr << 1);
		b.writeBytes(HexFormat.ofDelimiter(" ").parseHex("01 01 03 00 01 00 64 00 00 01"));
		return b;
	}

	private void advance(final long millis)
	{
		this.channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
		this.channel.runScheduledPendingTasks();
		this.channel.runPendingTasks();
	}

	private String next()
	{
		return LinkLayerTest.next(this.channel);
	}

	private List<String> drain()
	{
		final List<String> frames = new ArrayList<>();
		String frame;
		while ((frame = next()) != null) {
			frames.add(frame);
		}
		return frames;
	}

	private static String next(final EmbeddedChannel channel)
	{
		final ByteBuf out = channel.readOutbound();
		if (out == null) {
			return null;
		}
		try {
			return LinkLayerTest.HEX.formatHex(ByteBufUtil.getBytes(out));
		}
		finally {
			out.release();
		}
	}

	private static ByteBuf bytes(final String hex)
	{
		return Unpooled.wrappedBuffer(HexFormat.ofDelimiter(" ").parseHex(hex));
	}
}
