FROM bluenviron/mediamtx:1.20.1 AS mediamtx

FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl tzdata
COPY --from=mediamtx /mediamtx /mediamtx
COPY mediamtx.yml /mediamtx.yml
COPY event-hook.sh /usr/local/bin/pdk-mediamtx-event
RUN chmod 0755 /usr/local/bin/pdk-mediamtx-event
ENTRYPOINT ["/mediamtx"]
