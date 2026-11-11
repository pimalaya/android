#!/bin/sh
# Joins the demo video's clips (submission.md §3) into demo.mp4: every
# NN*.mp4 of the folder, in name order, scaled and padded to 1920x1080 at
# 30 fps, sound dropped. Prints each clip's start time, for the reply's
# timestamps (submission.md §5).
#
#   nix-shell -p ffmpeg --run 'sh assemble-video.sh <clips folder>'

set -eu

# A profile's LD_LIBRARY_PATH can load a newer glibc's libraries into ffmpeg.
unset LD_LIBRARY_PATH
# Byte order, so 02-mail sorts before 02b-mail whatever the locale.
LC_ALL=C
export LC_ALL

dir=${1:?usage: assemble-video.sh <clips folder>}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

: >"$work/list"
start=0
found=0
for clip in "$dir"/[0-9][0-9]*.mp4; do
    [ -e "$clip" ] || continue
    found=1
    name=$(basename "$clip")
    out="$work/$name"
    ffmpeg -nostdin -loglevel error -i "$clip" -an \
        -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black,fps=30,setsar=1,format=yuv420p" \
        -c:v libx264 -preset veryfast -crf 20 "$out"
    printf "file '%s'\n" "$out" >>"$work/list"
    awk -v s="$start" -v n="$name" \
        'BEGIN { printf "%02d:%02d  %s\n", int(s / 60), int(s % 60), n }'
    duration=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out")
    start=$(awk -v a="$start" -v b="$duration" 'BEGIN { print a + b }')
done

[ "$found" = 1 ] || { echo "no NN*.mp4 clip in $dir" >&2; exit 1; }

ffmpeg -nostdin -loglevel error -y -f concat -safe 0 -i "$work/list" \
    -c copy -movflags +faststart "$dir/demo.mp4"
end=$(awk -v s="$start" \
    'BEGIN { printf "%02d:%02d", int(s / 60), int(s % 60) }')
echo "$end  end, $dir/demo.mp4"
