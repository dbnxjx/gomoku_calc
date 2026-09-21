md dir
cd dir
md models
cd..
goto con


:sta


katago selfplay -models-dir  dir/models -config configs/selfplay.cfg -output-dir dir/selfplay  -max-games-total 10000
sh shuffle.sh dir ktmp 11 256


:con
sh train.sh dir b15c192 b15c192 256 main -lr-scale 0.3
sh export.sh stdf1 dir 0
sh train.sh dir b20c256 b20c256 256 extra -lr-scale 1.0
sh export.sh stdf1_20b dir 0

goto sta
sh train.sh dir b6c96 b6c96 256 extra
sh export.sh gomf1_6b dir 0
sh trainbig.sh dir b20c256 b20c256 256 extra
sh export.sh gomf1_20b dir 0
