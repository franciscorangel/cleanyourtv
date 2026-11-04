# tvprobe: o que os apps publicam para a Home

*English summary: a tiny diagnostic app that logs what other apps publish to the TV provider (Watch Next,
preview programs, channels). Used to find out what a custom Home can show on a given TV.*

App de diagnóstico (`br.casa.tvprobe`): lê o que os apps publicam no TvProvider ("Assistir a seguir",
programas de prévia e canais) e escreve uma linha por registro no logcat (tag `TvProbe`). Serve para descobrir,
numa TV nova, o que uma Home como a [Cinema](../cinema/README.md) consegue mostrar de cada app.

```bash
cd tvprobe && python3 build.py
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py --push tvprobe/build/tvprobe.apk /data/local/tmp/tvprobe.apk
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/tvprobe.apk; pm grant br.casa.tvprobe android.permission.READ_TV_LISTINGS; am start -n br.casa.tvprobe/.Probe; sleep 3; logcat -d -s TvProbe'
```

A saída traz títulos e progresso do que se assiste na TV: trate como dado pessoal e não publique sem revisar.
Para remover: `pm uninstall br.casa.tvprobe`.
