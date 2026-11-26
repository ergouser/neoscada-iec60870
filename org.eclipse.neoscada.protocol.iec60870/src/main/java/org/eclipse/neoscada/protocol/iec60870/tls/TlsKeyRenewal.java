/*******************************************************************************
 * Copyright (c) 2026 ErgoTech Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.neoscada.protocol.iec60870.tls;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * Renews a connection's session keys on a schedule, as IEC 62351-3 requires of a long-lived connection: once the
 * first handshake completes, every interval. In TLS 1.3 the JDK does this with a key update message, in TLS 1.2 with
 * a renegotiation; either way the 104 traffic carries on across it.
 * <p>
 * Only the client renews. Both may, but a renegotiation the server starts is refused by many TLS 1.2 clients, and a
 * peer that wants its own schedule can still start one.
 * </p>
 */
final class TlsKeyRenewal extends ChannelInboundHandlerAdapter {

	static final String NAME = "tls-key-renewal";

	private static final Logger logger = LoggerFactory.getLogger(TlsKeyRenewal.class);

	private final SslHandler ssl;

	private final Duration interval;

	private ScheduledFuture<?> renewal;

	TlsKeyRenewal(final SslHandler ssl, final Duration interval)
	{
		this.ssl = ssl;
		this.interval = interval;
	}

	@Override
	public void userEventTriggered(final ChannelHandlerContext ctx, final Object event) throws Exception
	{
		if (event instanceof SslHandshakeCompletionEvent) {
			final SslHandshakeCompletionEvent completion = (SslHandshakeCompletionEvent)event;
			if (completion.isSuccess()) {
				logger.debug("TLS session {} with {}: {}", this.ssl.engine().getSession().getProtocol(),
						ctx.channel().remoteAddress(), this.ssl.engine().getSession().getCipherSuite());
				schedule(ctx);
			}
			else {
				logger.warn("TLS handshake with {} failed: {}", ctx.channel().remoteAddress(),
						String.valueOf(completion.cause()));
			}
		}
		super.userEventTriggered(ctx, event);
	}

	private void schedule(final ChannelHandlerContext ctx)
	{
		if (this.renewal != null || this.interval.isZero() || !this.ssl.engine().getUseClientMode()) {
			return;
		}
		final long millis = this.interval.toMillis();
		this.renewal = ctx.executor().scheduleAtFixedRate(() -> renew(ctx), millis, millis, TimeUnit.MILLISECONDS);
	}

	private void renew(final ChannelHandlerContext ctx)
	{
		logger.debug("Renewing the TLS session keys with {}", ctx.channel().remoteAddress());
		this.ssl.renegotiate().addListener(future -> {
			if (!future.isSuccess()) {
				logger.warn("Renewing the TLS session keys with {} failed; closing", ctx.channel().remoteAddress(),
						future.cause());
				ctx.close();
			}
		});
	}

	@Override
	public void channelInactive(final ChannelHandlerContext ctx) throws Exception
	{
		if (this.renewal != null) {
			this.renewal.cancel(false);
			this.renewal = null;
		}
		super.channelInactive(ctx);
	}
}
