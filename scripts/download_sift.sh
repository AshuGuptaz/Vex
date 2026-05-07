#!/usr/bin/env bash
# Downloads the SIFT-1M ANN benchmark dataset to ~/sift (override via $1).
#
# Files: sift_base.fvecs, sift_query.fvecs, sift_groundtruth.ivecs.
# Source: ftp://ftp.irisa.fr/local/texmex/corpus/sift.tar.gz (~250 MB compressed,
# ~1 GB uncompressed). Mirror this with `wget` because curl on FTP is fiddly.
#
# Usage:
#   bash scripts/download_sift.sh           # downloads to ~/sift
#   bash scripts/download_sift.sh /tmp/data # downloads to /tmp/data/sift
#
# Then run the benchmark:
#   mvn -B -pl bench -am -Pbench-sift -Dsift.dir=$HOME/sift verify

set -euo pipefail

DEST="${1:-$HOME/sift}"
mkdir -p "$DEST"
cd "$DEST"

if [[ -f sift_base.fvecs && -f sift_query.fvecs && -f sift_groundtruth.ivecs ]]; then
  echo "All files already present in $DEST. Nothing to do."
  exit 0
fi

URL="ftp://ftp.irisa.fr/local/texmex/corpus/sift.tar.gz"
ALT_URL="https://dl.fbaipublicfiles.com/billion-scale-ann-benchmarks/bigann/sift.tar.gz"
TARBALL="sift.tar.gz"

if command -v wget >/dev/null 2>&1; then
  DL=(wget -O "$TARBALL")
elif command -v curl >/dev/null 2>&1; then
  DL=(curl -fLo "$TARBALL")
else
  echo "Need wget or curl on PATH." >&2
  exit 1
fi

if ! "${DL[@]}" "$URL"; then
  echo "Primary source unreachable; trying mirror..."
  "${DL[@]}" "$ALT_URL"
fi

tar -xzf "$TARBALL"
mv sift/*.fvecs sift/*.ivecs "$DEST"/ 2>/dev/null || true
rmdir sift 2>/dev/null || true
rm -f "$TARBALL"

echo "Done. Files in $DEST:"
ls -lh "$DEST"
