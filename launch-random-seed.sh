#!/bin/bash
C4KPSEED1=$(echo "$RANDOM$RANDOM$(date +%s%N)$RANDOM$(date +%s%N)" | tail -c 6)
C4KPSEED2=$(echo "$RANDOM$RANDOM$(date +%s%N)$RANDOM$(date +%s%N)" | head -c 9)
C4KPSEEDF=$(echo $C4KPSEED1$C4KPSEED2)
echo "The current seed: $C4KPSEEDF"
java -jar Craft4kPlus.jar --seed $C4KPSEEDF
