/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.tests;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.neoscada.protocol.iec60870.ProtocolOptions;
import org.eclipse.neoscada.protocol.iec60870.apci.MessageChannel;
import org.eclipse.neoscada.protocol.iec60870.asdu.MessageManager;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.ASDUAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.InformationObjectAddress;
import org.eclipse.neoscada.protocol.iec60870.asdu.types.Value;
import org.eclipse.neoscada.protocol.iec60870.client.AutoConnectClient;
import org.eclipse.neoscada.protocol.iec60870.client.Client;
import org.eclipse.neoscada.protocol.iec60870.client.ClientModule;
import org.eclipse.neoscada.protocol.iec60870.client.data.DataListener;
import org.eclipse.neoscada.protocol.iec60870.client.data.DataModule;
import org.eclipse.neoscada.protocol.iec60870.client.data.DataModuleHandler;
import org.eclipse.neoscada.protocol.iec60870.client.data.DataModuleOptions;
import org.eclipse.neoscada.protocol.iec60870.client.data.DataProcessor;
import org.eclipse.neoscada.protocol.iec60870.tls.TlsOptions;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

/**
 * A controlling station: an {@link AutoConnectClient} with the data module, recording every value it is given, every
 * other message that reaches it (command confirmations, end of initialisation) and its connection states.
 */
final class TestClient implements AutoCloseable {

	final Map<Integer, Value<?>> values = new ConcurrentHashMap<>();

	final List<Object> messages = new CopyOnWriteArrayList<>();

	/** Every message as it arrives, data included, in order. */
	final List<Object> received = new CopyOnWriteArrayList<>();

	final List<AutoConnectClient.State> states = new CopyOnWriteArrayList<>();

	final List<Throwable> errors = new CopyOnWriteArrayList<>();

	final AtomicInteger started = new AtomicInteger();

	/** TLS handshakes completed, the first and any renegotiation. */
	final AtomicInteger handshakes = new AtomicInteger();

	final AutoConnectClient client;

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	TestClient(final InetSocketAddress server, final ProtocolOptions options, final TlsOptions tls)
	{
		// the common address itself, not the broadcast address, which the simulator does not answer
		final DataProcessor processor = new DataProcessor(this.executor, new DataListener() {

			@Override
			public void started()
			{
				TestClient.this.started.incrementAndGet();
			}

			@Override
			public void update(final ASDUAddress commonAddress, final InformationObjectAddress objectAddress,
					final Value<?> value)
			{
				TestClient.this.values.put(objectAddress.getAddress(), value);
			}

			@Override
			public void disconnected()
			{
			}
		}, false, List.of(ASDUAddress.valueOf(1)));
		this.client = new AutoConnectClient(server.getHostString(), server.getPort(), options,
				() -> List.of(new DataModule(processor, new DataModuleOptions.Builder().build()), new Capture()),
				(state, e) -> {
					this.states.add(state);
					if (e != null) {
						this.errors.add(e);
					}
				}, tls);
	}

	boolean isConnected()
	{
		return !this.states.isEmpty() && this.states.get(this.states.size() - 1) == AutoConnectClient.State.CONNECTED;
	}

	@Override
	public void close() throws Exception
	{
		this.client.close();
		this.executor.shutdownNow();
	}

	/** Puts a handler after the data module's, where the messages it does not take arrive. */
	private final class Capture implements ClientModule {

		@Override
		public void initializeClient(final Client client, final MessageManager manager)
		{
		}

		@Override
		public void initializeChannel(final SocketChannel channel, final MessageChannel messageChannel)
		{
			final String data = channel.pipeline().context(DataModuleHandler.class).name();
			channel.pipeline().addBefore(data, "received", new ChannelInboundHandlerAdapter() {

				@Override
				public void channelRead(final ChannelHandlerContext ctx, final Object msg)
				{
					TestClient.this.received.add(msg);
					ctx.fireChannelRead(msg);
				}
			});
			channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

				@Override
				public void channelRead(final ChannelHandlerContext ctx, final Object msg)
				{
					TestClient.this.messages.add(msg);
				}

				@Override
				public void userEventTriggered(final ChannelHandlerContext ctx, final Object event)
				{
					if (event instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent)event).isSuccess()) {
						TestClient.this.handshakes.incrementAndGet();
					}
					ctx.fireUserEventTriggered(event);
				}
			});
		}

		@Override
		public void requestStartData()
		{
		}

		@Override
		public void dispose()
		{
		}
	}
}
