#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p android/app/src/test/resources
# Same signal with both one-packet and batched pages; duration includes a partial final packet.
for page_duration in 20000 1000000; do
  ffmpeg -hide_banner -loglevel error -y -f lavfi \
    -i 'sine=frequency=440:sample_rate=48000:duration=2.037' \
    -ac 1 -c:a libopus -b:a 32k -vbr off -frame_duration 20 -page_duration "$page_duration" \
    -f opus "android/app/src/test/resources/tone-$page_duration.opus"
done
