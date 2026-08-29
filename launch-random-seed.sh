#!/bin/bash
set -e

if [ "$#" -gt 1 ]; then
    echo "Usage: $0 [save-file]"
    exit 1
fi

seed="$(od -An -N8 -td8 /dev/urandom | tr -d ' ')"
save_file="${1:-random-save.c4ks}"
echo "The current seed: $seed"
java -jar Craft4kPlus.jar --seed "$seed" --save "$save_file"
