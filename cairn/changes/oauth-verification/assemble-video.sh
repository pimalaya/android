#!/bin/sh
# Joins the demo video's clips (submission.md §3) into demo.mp4: every
# NN*.mp4 of the folder, in name order, scaled and padded to 1920x1080 at
# 30 fps, sound dropped. A NN*.txt beside them becomes a 4-second title card,
# white on black, before the clip of the same name, or in its own place when
# no clip has its name. Prints where each card and clip starts, for the
# reply's timestamps (submission.md §5). Reads the clips, never changes them.
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

scale="scale=1920:1080:force_original_aspect_ratio=decrease"
scale="$scale,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black"
scale="$scale,fps=30,setsar=1,format=yuv420p"
card="drawtext=textfile=%s:fontcolor=white:fontsize=52:line_spacing=24"
card="$card:text_align=C:x=(w-text_w)/2:y=(h-text_h)/2"

stamp() {
    awk -v s="$1" -v n="$2" \
        'BEGIN { printf "%02d:%02d  %s\n", int(s / 60), int(s % 60), n }'
}

# Appends a part to the list, prints where it starts, moves the clock on.
append() {
    printf "file '%s'\n" "$1" >>"$work/list"
    stamp "$start" "$2"
    duration=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$1")
    start=$(awk -v a="$start" -v b="$duration" 'BEGIN { print a + b }')
}

: >"$work/list"
start=0
stems=$(for file in "$dir"/[0-9][0-9]*.mp4 "$dir"/[0-9][0-9]*.txt; do
    [ -e "$file" ] && basename "$file" | sed 's/\.[a-z0-9]*$//'
done | sort -u)
[ -n "$stems" ] || { echo "no NN*.mp4 clip in $dir" >&2; exit 1; }

for stem in $stems; do
    if [ -e "$dir/$stem.txt" ]; then
        out="$work/$stem.card.mp4"
        filter=$(printf "$card" "$dir/$stem.txt")
        ffmpeg -nostdin -loglevel error -f lavfi \
            -i color=c=black:s=1920x1080:r=30:d=4 -vf "$filter,format=yuv420p" \
            -c:v libx264 -preset veryfast -crf 20 "$out"
        append "$out" "$stem (title)"
    fi
    if [ -e "$dir/$stem.mp4" ]; then
        out="$work/$stem.mp4"
        ffmpeg -nostdin -loglevel error -i "$dir/$stem.mp4" -an -vf "$scale" \
            -c:v libx264 -preset veryfast -crf 20 "$out"
        append "$out" "$stem.mp4"
    fi
done

ffmpeg -nostdin -loglevel error -y -f concat -safe 0 -i "$work/list" \
    -c copy -movflags +faststart "$dir/demo.mp4"
stamp "$start" "end, $dir/demo.mp4"
