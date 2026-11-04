# tvkeys: botões coloridos e Apps do controle, sem root

*English summary: remaps the remote's four colour keys and the Apps key to open apps, with a key layout overlay
(KCM) selected through the normal InputManager API from the ADB shell. No root, no system files changed, no
accessibility key filter. Built for the Sony KD-65X755F IR remote (`mtkinp_ir_events`, Android TV 9).*

App `br.casa.tvkeys` (~17 KB, sem permissões, sem internet).

| Botão | Scan | Vira | Abre |
|---|---|---|---|
| Vermelho | 398 | `CALENDAR` | o app escolhido na Cinema (padrão: YouTube) |
| Verde | 399 | `CONTACTS` | idem (padrão: HBO Max) |
| Amarelo | 400 | `CALCULATOR` | idem (padrão: Globoplay) |
| Azul | 401 | `ENVELOPE` | idem (padrão: Stremio) |
| Apps | 580 | `EXPLORER` | a grade de Apps da Cinema |

Como funciona: o overlay `res/raw/cores.kcm` troca só essas cinco teclas por teclas de "abrir app" do Android.
O Android abre, para cada uma, a atividade invisível do `tvkeys` na categoria correspondente
(`APP_CALENDAR` etc.). Com a [Cinema](../cinema/README.md) instalada, o `tvkeys` só repassa a cor e a Cinema
abre o app escolhido em **Ajustes → Botões do controle**. Sem a Cinema, abre os apps padrão da tabela.

Outro controle ou outra TV: confira os códigos com `getevent -l` e o nome do dispositivo com
`dumpsys input`, e ajuste `cores.kcm`, `original.kcm` e `DEVICE` em `shell/LayoutCtl.java`.

## Compilar

Mesmos requisitos da Cinema (JDK 17 a 22, Android SDK com platform 34 e build-tools 34.0.0):

```bash
cd tvkeys && python3 build.py
```

Gera `build/tvkeys.apk` e `build/layoutctl.dex` (o seletor de layout que roda no shell).

## Instalar, com teste e reversão automática

```bash
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py --push tvkeys/build/tvkeys.apk /data/local/tmp/tvkeys.apk
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py --push tvkeys/build/layoutctl.dex /data/local/tmp/tvkeys-layoutctl.dex
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/tvkeys.apk; rm -f /data/local/tmp/tvkeys.apk'
```

Num terminal, aplique em modo de teste por 15 minutos (o comando fica esperando):

```bash
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py 'CLASSPATH=/data/local/tmp/tvkeys-layoutctl.dex app_process /system/bin LayoutCtl trial /data/local/tmp/tvkeys-trial.json 900'
```

Teste os cinco botões e confira que volume, Home, Voltar e Entradas continuam normais. Para manter, confirme
em outro terminal antes dos 15 minutos; sem confirmação, tudo volta sozinho:

```bash
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py 'touch /data/local/tmp/tvkeys-trial.json.commit'
```

Guarde `/data/local/tmp/tvkeys-layoutctl.dex` e `tvkeys-trial.json` na TV: são o que permite desfazer. A
escolha continua valendo depois de reiniciar.

## Desfazer

```bash
TV_HOST=<ip da TV> tools/venv/bin/python -I tools/tv.py 'CLASSPATH=/data/local/tmp/tvkeys-layoutctl.dex app_process /system/bin LayoutCtl restore /data/local/tmp/tvkeys-trial.json'
```

O `restore` aplica primeiro o overlay reverso (`original`), porque os overlays se somam no mapa ativo, e só
depois volta à seleção anterior. Só desinstalar o app não basta. Depois disso, se quiser:
`pm uninstall br.casa.tvkeys`.

`shell/LayoutCtl.java` foi adaptado do companion `sony-apps-layout` de Lzchyi (MIT).
