# The interoperability oracle: IEC60870-5-104-Simulator's core (MIT, https://github.com/Karl-Dai/IEC60870-5-104-Simulator)
# behind a small command line. Built and run only by the interop task; nothing from it is shipped.
FROM rust:1-bookworm AS build
WORKDIR /src
COPY oracle /src
RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/iec104-oracle /usr/local/bin/iec104-oracle
ENTRYPOINT ["/usr/local/bin/iec104-oracle"]
