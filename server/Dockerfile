# Vendored at image build time and embedded in the server binary.
FROM curlimages/curl:8.11.1 AS vendor
USER root
RUN mkdir -p /vendor \
    && curl --fail --location --retry 3 --output /vendor/jszip.min.js https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js \
    && curl --fail --location --retry 3 --output /vendor/epub.min.js https://cdn.jsdelivr.net/npm/epubjs@0.3.93/dist/epub.min.js

FROM rust:1.90-bookworm AS build
WORKDIR /src
COPY Cargo.toml ./
COPY src ./src
COPY static ./static
COPY --from=vendor /vendor/ ./static/vendor/
RUN cargo test --release && cargo build --release

FROM debian:bookworm-slim
RUN groupadd --system --gid 10001 readcore \
    && useradd --system --uid 10001 --gid 10001 --home-dir /data readcore \
    && mkdir -p /data /books && chown readcore:readcore /data
COPY --from=build /src/target/release/readcore-server /usr/local/bin/readcore-server
USER 10001:10001
ENV READCORE_LIBRARY=/books DATABASE_URL=sqlite:///data/readcore.db RUST_LOG=info
EXPOSE 3435
VOLUME ["/data"]
CMD ["readcore-server"]
