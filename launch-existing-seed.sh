#!/bin/bash
set -e

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: $0 <seed> [save-file]"
    exit 1
fi

seed="$1"
save_file="${2:-save-${seed}.c4ks}"
echo "The current seed: $seed"
java -jar Craft4kPlus.jar --seed "$seed" --save "$save_file"
