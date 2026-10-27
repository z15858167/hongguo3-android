#!/bin/sh
# 本地验证"按区间流式解密"与"整文件解密"逐字节一致。
# 需要一份真实密文 mp4（上游原片，未解密）。key 可以随便给：
# 这里比的是两条解密路径是否一致，与 key 本身对不对无关。
#
#   sh tools/rangetest/run.sh /path/to/xxx.enc
#
# 依赖：JDK 17（JAVA_HOME 或 PATH 里的 javac/java）
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENC=${1:?用法: sh tools/rangetest/run.sh <密文.mp4> [keyHex]}
KEY=${2:-00112233445566778899aabbccddeeff}

JH=${JAVA_HOME:-}
if [ -z "$JH" ]; then
  JH=$(dirname "$(dirname "$(command -v javac)")")
fi
OUT=$(mktemp -d)
"$JH/bin/javac" -nowarn -d "$OUT" \
  "$ROOT/app/src/main/java/com/hongguo/client/cenc/Boxes.java" \
  "$ROOT/app/src/main/java/com/hongguo/client/cenc/Ctr.java" \
  "$ROOT/app/src/main/java/com/hongguo/client/cenc/Moov.java" \
  "$ROOT/app/src/main/java/com/hongguo/client/cenc/Decryptor.java" \
  "$ROOT/app/src/main/java/com/hongguo/client/cenc/Ranger.java" \
  "$ROOT/tools/rangetest/RangerTest.java"
"$JH/bin/java" -cp "$OUT" RangerTest "$ENC" "$KEY"
rm -rf "$OUT"
