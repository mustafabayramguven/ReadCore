FROM rust:1.90-bookworm AS build
WORKDIR /src
COPY Cargo.toml ./
COPY src ./src
RUN cargo test --release && cargo build --release

FROM debian:bookworm-slim
RUN groupadd --system --gid 10001 readcore \
    && useradd --system --uid 10001 --gid 10001 --home-dir /data readcore \
    && mkdir -p /data /books && chown readcore:readcore /data
COPY --from=build /src/target/release/readcore-server /usr/local/bin/readcore-server
USER 10001:10001
ENV READCORE_LIBRARY=/books DATABASE_URL=sqlite:///data/readcore.db RUST_LOG=info
EXPOSE 8080
VOLUME ["/data"]
CMD ["readcore-server"]
